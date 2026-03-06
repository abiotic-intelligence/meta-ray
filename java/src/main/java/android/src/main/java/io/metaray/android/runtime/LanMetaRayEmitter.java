package io.metaray.android.runtime;

import core.api.MetaRayEmitter;
import core.api.MetaRayReceiver;
import core.model.ActionRequest;
import core.model.ActionResponse;
import core.model.AssetBytes;
import core.model.AssetDescriptor;
import core.model.AssetFetchRequest;
import core.model.AssetRegistration;
import core.model.ContextFrame;
import core.model.EmitterConfig;
import core.model.PullOptions;
import core.model.wire.AuthProof;
import core.model.wire.AuthMessage;
import core.model.wire.ErrorMessage;
import core.model.wire.MsgType;
import core.model.wire.PullMessage;
import core.model.wire.WireMessage;
import core.spi.Codec;
import core.spi.CryptoProvider;
import core.spi.PacketHandler;
import core.util.Checks;
import core.util.MetaRayException;
import io.metaray.android.codec.JacksonCborCodec;
import io.metaray.android.codec.JacksonJsonCodec;
import io.metaray.android.crypto.AesGcmCryptoProvider;
import io.metaray.android.discovery.NsdAdvertiser;
import io.metaray.android.transport.TlsControlServer;
import io.metaray.android.transport.UdpMulticastTransport;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLServerSocketFactory;

public final class LanMetaRayEmitter implements MetaRayEmitter, AutoCloseable {
  private static final String SECURITY_TLS = "tls";
  private static final String EXT_NEGOTIATION_SUPPORTED_CODECS = "metaray.negotiation.supportedCodecs";
  private static final String EXT_NEGOTIATION_SUPPORTED_SECURITY = "metaray.negotiation.supportedSecurity";
  private static final String EXT_NEGOTIATION_SELECTED_CODEC = "metaray.negotiation.selectedCodec";
  private static final String EXT_NEGOTIATION_SELECTED_SECURITY = "metaray.negotiation.selectedSecurity";
  private static final String EXT_PUSH_GROUP_KEY = "metaray.push.groupKey";
  private static final String EXT_PUSH_CIPHER = "metaray.push.cipher";
  private static final String PUSH_CIPHER_AES_GCM = "aes-gcm";
  private static final int PUSH_GROUP_KEY_BYTES = 32;
  private static final long PAIR_REQUEST_TTL_MS = 2 * 60 * 1000L;
  private static final long CHALLENGE_TTL_MS = 60 * 1000L;

  private final EmitterConfig info;
  private final JacksonJsonCodec bootstrapCodec;
  private final Map<String, Codec> codecsByName;
  private final Set<String> emitterCodecs;
  private final Set<String> emitterSecurity;
  private final NsdAdvertiser advertiser;
  private final TlsControlServer tlsControlServer;
  private final String activeControlSecurity;
  private final UdpMulticastTransport multicast;
  private final byte[] pushGroupKey;
  private final CryptoProvider pushCrypto;

  private final AtomicBoolean advertising = new AtomicBoolean(false);
  private final AtomicBoolean controlRunning = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private final AtomicLong assetSeq = new AtomicLong(0);
  private final AtomicLong responseSeq = new AtomicLong(0);
  private final SecureRandom secureRandom = new SecureRandom();
  private final ConcurrentMap<String, java.util.List<ContextFrame>> framesByContextId = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ReceiverRegistryEntry> receiverRegistry = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Long> pairRequiredByKeyId = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, PendingChallenge> pendingChallengeByKeyId = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, PendingNegotiation> pendingNegotiationByKeyId = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AssetDescriptor> assetsById = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AssetDescriptor> assetsByHash = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, byte[]> assetBytes = new ConcurrentHashMap<>();
  private final AtomicReference<PacketHandler<ActionRequest>> actionHandler = new AtomicReference<>();
  private final AtomicReference<PairingApprovalPolicy> firstPairingApprovalPolicy =
      new AtomicReference<>(request -> PairingApprovalDecision.DECLINE);

  public LanMetaRayEmitter(EmitterConfig info, NsdAdvertiser advertiser) {
    this(info, advertiser, new JacksonJsonCodec(), new JacksonCborCodec(), null);
  }

  public LanMetaRayEmitter(EmitterConfig info, NsdAdvertiser advertiser, JacksonJsonCodec codec) {
    this(info, advertiser, codec, new JacksonCborCodec(), null);
  }

  public LanMetaRayEmitter(
      EmitterConfig info,
      NsdAdvertiser advertiser,
      JacksonJsonCodec jsonCodec,
      JacksonCborCodec cborCodec,
      SSLServerSocketFactory tlsServerSocketFactory
  ) {
    this.info = Checks.notNull(info, "info");
    this.bootstrapCodec = Checks.notNull(jsonCodec, "jsonCodec");
    this.advertiser = Checks.notNull(advertiser, "advertiser");

    Map<String, Codec> map = new HashMap<>();
    map.put("json", bootstrapCodec);
    map.put("cbor", Checks.notNull(cborCodec, "cborCodec"));
    this.codecsByName = Map.copyOf(map);
    this.emitterCodecs = normalizeLower(info.codecs);
    this.emitterSecurity = normalizeLower(info.security);
    this.activeControlSecurity = chooseControlSecurity(this.emitterSecurity);
    this.pushGroupKey = generatePushGroupKey();
    this.pushCrypto = new AesGcmCryptoProvider(this.pushGroupKey);

    SSLServerSocketFactory factory = tlsServerSocketFactory != null
        ? tlsServerSocketFactory
        : (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
    this.tlsControlServer = new TlsControlServer(info.controlPort, factory,
        io.metaray.android.internal.AndroidExecutors.newCachedIoExecutor("metaray-android-tls-control-server-io"));

    if (info.multicastPort != null) {
      this.multicast = new UdpMulticastTransport(info.multicastPort);
      if (info.multicastGroup != null) {
        this.multicast.join(new java.net.InetSocketAddress(info.multicastGroup, info.multicastPort));
      }
    } else {
      this.multicast = null;
    }
  }

  @Override
  public CompletableFuture<Void> startAdvertisingAsync() {
    try {
      ensureNotShutdown();
      advertiser.start(info);
      advertising.set(true);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> stopAdvertisingAsync() {
    try {
      advertiser.stop();
      advertising.set(false);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> startControlServerAsync() {
    try {
      ensureNotShutdown();
      tlsControlServer.start(this::handleControlRequest);
      controlRunning.set(true);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> stopControlServerAsync() {
    try {
      tlsControlServer.stop();
      controlRunning.set(false);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> pushEventAsync(ContextFrame eventFrame) {
    try {
      ensureNotShutdown();
      ContextFrame event = Checks.notNull(eventFrame, "eventFrame");
      framesByContextId.computeIfAbsent(event.std.contextId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(event);
      if (multicast == null) return CompletableFuture.completedFuture(null);
      byte[] payload = bootstrapCodec.encode(event);
      byte[] encryptedPayload = pushCrypto.seal(payload);
      return multicast.send(encryptedPayload);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<AssetDescriptor> registerAssetAsync(AssetRegistration reg) {
    try {
      ensureNotShutdown();
      AssetRegistration registration = Checks.notNull(reg, "reg");
      String hash = sha256(registration.bytes);
      String id = registration.suggestedId != null ? registration.suggestedId : "asset-" + assetSeq.incrementAndGet();
      AssetDescriptor descriptor = new AssetDescriptor(
          id,
          registration.type,
          hash,
          (long) registration.bytes.length,
          "/asset/" + id,
          registration.ttlMs
      );
      assetsById.put(descriptor.id, descriptor);
      assetsByHash.put(descriptor.hash, descriptor);
      assetBytes.put(descriptor.id, registration.bytes.clone());
      return CompletableFuture.completedFuture(descriptor);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> onActionAsync(PacketHandler<ActionRequest> handler) {
    try {
      ensureNotShutdown();
      actionHandler.set(Checks.notNull(handler, "handler"));
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> shutdownAsync() {
    try {
      if (shutdown.compareAndSet(false, true)) {
        advertiser.stop();
        advertising.set(false);
        tlsControlServer.stop();
        controlRunning.set(false);
        sessions.clear();
        pairRequiredByKeyId.clear();
        pendingChallengeByKeyId.clear();
        pendingNegotiationByKeyId.clear();
        if (multicast != null) multicast.close();
        tlsControlServer.close();
      }
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void close() {
    MetaRayReceiver.await(shutdownAsync());
  }

  public void registerReceiver(String receiverKeyId, String receiverId, String receiverPublicKey) {
    String keyId = Checks.notBlank(receiverKeyId, "receiverKeyId");
    String publicKey = Checks.notBlank(receiverPublicKey, "receiverPublicKey");
    AuthProof.requireKeyIdMatchesPublicKey(keyId, publicKey);
    long now = System.currentTimeMillis();
    ReceiverRegistryEntry entry = new ReceiverRegistryEntry(
        keyId,
        Checks.notBlank(receiverId, "receiverId"),
        publicKey,
        now,
        now
    );
    receiverRegistry.put(keyId, entry);
  }

  public boolean isReceiverRegistered(String receiverKeyId) {
    return receiverRegistry.containsKey(Checks.notBlank(receiverKeyId, "receiverKeyId"));
  }

  public void setFirstPairingApprovalPolicy(PairingApprovalPolicy policy) {
    firstPairingApprovalPolicy.set(Checks.notNull(policy, "policy"));
  }

  private byte[] handleControlRequest(byte[] requestBytes) {
    Inbound inbound = null;
    try {
      inbound = decodeInbound(requestBytes);
      WireMessage<?> response = switch (inbound.wire.std.msgType) {
        case AUTH -> onAuth(inbound);
        case PULL -> onPullContext(inbound);
        case ASSET_FETCH_REQUEST -> onFetchAsset(inbound);
        case ACTION_REQUEST -> onSendAction(inbound);
        default -> errorWire(
            inbound.wire.std.header.sessionId,
            inbound.wire.std.msgType,
            -1L,
            "UNSUPPORTED_MSG_TYPE",
            "Unsupported control request type: " + inbound.wire.std.msgType
        );
      };
      Codec responseCodec = responseCodec(inbound, response);
      return responseCodec.encode(response);
    } catch (RuntimeException e) {
      Codec codec = inbound != null ? inbound.codec : bootstrapCodec;
      String sessionId = (inbound != null) ? inbound.wire.std.header.sessionId : ControlWire.BOOTSTRAP_SESSION_ID;
      MsgType failedType = (inbound != null) ? inbound.wire.std.msgType : MsgType.AUTH;
      long failedSeq = -1L;
      return codec.encode(errorWire(sessionId, failedType, failedSeq, "CONTROL_ERROR", e.getMessage()));
    }
  }

  private WireMessage<?> onAuth(Inbound inbound) {
    ensureReadyForConnect();
    AuthMessage request = ControlWire.bodyAs(inbound.wire, AuthMessage.class);
    if (!info.appId.equalsIgnoreCase(inbound.wire.std.header.appId)) {
      return authWire(AuthMessage.deny("appIdMismatch"), false);
    }
    return switch (request.step) {
      case "hello" -> onAuthHello(inbound);
      case "pair" -> onAuthPair(inbound);
      case "proof" -> onAuthProof(inbound, request);
      default -> authWire(AuthMessage.deny("unsupportedAuthStep"), false);
    };
  }

  private WireMessage<?> onAuthHello(Inbound inbound) {
    String receiverKeyId = inbound.wire.std.header.keyId;
    if (receiverKeyId == null || receiverKeyId.isBlank()) {
      return authWire(AuthMessage.deny("missingKeyId"), false);
    }
    List<String> requestedCodecs = extStringList(inbound.wire.ext, EXT_NEGOTIATION_SUPPORTED_CODECS);
    List<String> requestedSecurity = extStringList(inbound.wire.ext, EXT_NEGOTIATION_SUPPORTED_SECURITY);
    String selectedCodec = chooseRequestedCodec(requestedCodecs);
    if (selectedCodec == null) {
      pendingNegotiationByKeyId.remove(receiverKeyId);
      return authWire(AuthMessage.deny("unsupportedCodec"), false);
    }
    String selectedSecurity = chooseRequestedSecurity(requestedSecurity);
    if (selectedSecurity == null) {
      pendingNegotiationByKeyId.remove(receiverKeyId);
      return authWire(AuthMessage.deny("unsupportedSecurity"), false);
    }
    pendingNegotiationByKeyId.put(receiverKeyId, new PendingNegotiation(selectedCodec, selectedSecurity));
    ReceiverRegistryEntry entry = receiverRegistry.get(receiverKeyId);
    if (entry == null) {
      pairRequiredByKeyId.put(receiverKeyId, System.currentTimeMillis());
      return authWire(AuthMessage.pairRequired(), true);
    }
    entry.lastSeenEpochMs = System.currentTimeMillis();
    return authWire(buildChallenge(receiverKeyId), false);
  }

  private WireMessage<?> onAuthPair(Inbound inbound) {
    WireMessage.Header header = inbound.wire.std.header;
    String receiverKeyId = header.keyId;
    if (receiverKeyId == null || receiverKeyId.isBlank()) {
      return authWire(AuthMessage.deny("missingKeyId"), false);
    }
    String receiverId = header.id;
    if (receiverId == null || receiverId.isBlank()) {
      return authWire(AuthMessage.deny("missingReceiverId"), false);
    }
    String receiverPublicKey = header.publicKey;
    if (receiverPublicKey == null || receiverPublicKey.isBlank()) {
      return authWire(AuthMessage.deny("missingPublicKey"), false);
    }
    Long pairRequiredAt = pairRequiredByKeyId.remove(receiverKeyId);
    if (pairRequiredAt == null || System.currentTimeMillis() - pairRequiredAt > PAIR_REQUEST_TTL_MS) {
      return authWire(AuthMessage.deny("pairNotRequired"), false);
    }
    try {
      AuthProof.requireKeyIdMatchesPublicKey(receiverKeyId, receiverPublicKey);
    } catch (IllegalArgumentException invalidPair) {
      pendingNegotiationByKeyId.remove(receiverKeyId);
      return authWire(AuthMessage.deny("invalidPair"), false);
    }
    PairingApprovalDecision decision;
    try {
      decision = firstPairingApprovalPolicy.get().decide(
          new PairingApprovalRequest(receiverKeyId, receiverId, receiverPublicKey, header.deviceName)
      );
    } catch (RuntimeException policyError) {
      pendingNegotiationByKeyId.remove(receiverKeyId);
      return authWire(AuthMessage.deny("pairingApprovalFailed"), false);
    }
    if (decision != PairingApprovalDecision.ACCEPT) {
      pendingNegotiationByKeyId.remove(receiverKeyId);
      return authWire(AuthMessage.deny("userDeclined"), false);
    }
    long now = System.currentTimeMillis();
    receiverRegistry.put(
        receiverKeyId,
        new ReceiverRegistryEntry(receiverKeyId, receiverId, receiverPublicKey, now, now)
    );
    return authWire(buildChallenge(receiverKeyId), false);
  }

  private WireMessage<?> onAuthProof(Inbound inbound, AuthMessage request) {
    String receiverKeyId = inbound.wire.std.header.keyId;
    if (receiverKeyId == null || receiverKeyId.isBlank()) {
      return authWire(AuthMessage.deny("invalidProof"), false);
    }
    String receiverId = inbound.wire.std.header.id;
    if (receiverId == null || receiverId.isBlank()) {
      return authWire(AuthMessage.deny("invalidProof"), false);
    }
    ReceiverRegistryEntry entry = receiverRegistry.get(receiverKeyId);
    if (entry == null) {
      return authWire(AuthMessage.deny("invalidProof"), false);
    }
    PendingChallenge challenge = pendingChallengeByKeyId.remove(receiverKeyId);
    if (challenge == null) {
      return authWire(AuthMessage.deny("invalidProof"), false);
    }
    if (System.currentTimeMillis() - challenge.createdAtEpochMs > CHALLENGE_TTL_MS) {
      return authWire(AuthMessage.deny("invalidProof"), false);
    }
    if (!challenge.nonce.equals(request.nonce)) {
      return authWire(AuthMessage.deny("invalidProof"), false);
    }
    if (!entry.receiverId.equals(receiverId)) {
      return authWire(AuthMessage.deny("invalidProof"), false);
    }
    byte[] payload = AuthProof.canonicalPayload(info.emitterId, receiverKeyId, request.nonce);
    boolean valid = request.proof != null
        && AuthProof.verifyEd25519(entry.receiverPublicKey, payload, request.proof);
    if (!valid) {
      return authWire(AuthMessage.deny("invalidProof"), false);
    }
    PendingNegotiation negotiation = pendingNegotiationByKeyId.remove(receiverKeyId);
    if (negotiation == null) {
      return authWire(AuthMessage.deny("missingNegotiation"), false);
    }
    Codec selectedCodec = codecsByName.get(negotiation.selectedCodec);
    if (selectedCodec == null) {
      return authWire(AuthMessage.deny("unsupportedCodec"), false);
    }
    if (!activeControlSecurity.equals(negotiation.selectedSecurity)) {
      return authWire(AuthMessage.deny("unsupportedSecurity"), false);
    }
    entry.lastSeenEpochMs = System.currentTimeMillis();
    String sessionId = UUID.randomUUID().toString();
    sessions.put(
        sessionId,
        new SessionState(
            sessionId,
            negotiation.selectedCodec,
            negotiation.selectedSecurity,
            selectedCodec,
            receiverKeyId
        )
    );
    return authWire(
        AuthMessage.ok(sessionId),
        false,
        Map.of(
            EXT_PUSH_GROUP_KEY, Base64.getUrlEncoder().withoutPadding().encodeToString(pushGroupKey),
            EXT_PUSH_CIPHER, PUSH_CIPHER_AES_GCM,
            EXT_NEGOTIATION_SELECTED_CODEC, negotiation.selectedCodec,
            EXT_NEGOTIATION_SELECTED_SECURITY, negotiation.selectedSecurity
        )
    );
  }

  private AuthMessage buildChallenge(String receiverKeyId) {
    String nonce = generateNonce();
    pendingChallengeByKeyId.put(receiverKeyId, new PendingChallenge(nonce, System.currentTimeMillis()));
    return AuthMessage.challenge(nonce);
  }

  private WireMessage<?> onPullContext(Inbound inbound) {
    SessionState session = requireSession(inbound);
    PullMessage request = ControlWire.bodyAs(inbound.wire, PullMessage.class);
    List<ContextFrame> source = framesByContextId.get(request.contextId);
    List<ContextFrame> frames = selectFrames(source, request.mode, request.limit);
    PullMessage body = new PullMessage(request.contextId, request.mode, request.limit, frames);
    return wire(MsgType.PULL, session.sessionId, body, info.emitterId);
  }

  private static List<ContextFrame> selectFrames(
      List<ContextFrame> source,
      PullOptions.Mode mode,
      Integer limit
  ) {
    if (source == null || source.isEmpty()) return List.of();
    if (mode == PullOptions.Mode.ALL) {
      return List.copyOf(source);
    }
    if (mode == PullOptions.Mode.LATEST) {
      return List.of(source.get(source.size() - 1));
    }
    int size = source.size();
    int from = Math.max(0, size - Checks.notNull(limit, "limit"));
    return List.copyOf(source.subList(from, size));
  }

  private WireMessage<?> onFetchAsset(Inbound inbound) {
    SessionState session = requireSession(inbound);
    AssetFetchRequest fetch = ControlWire.bodyAs(inbound.wire, AssetFetchRequest.class);
    AssetDescriptor descriptor = fetch.assetId != null ? assetsById.get(fetch.assetId) : assetsByHash.get(fetch.hash);
    if (descriptor == null) {
      String id = fetch.assetId != null ? fetch.assetId : fetch.hash;
      return wire(MsgType.ASSET_FETCH_RESPONSE, session.sessionId, AssetBytes.notFound(id));
    }
    byte[] bytes = assetBytes.get(descriptor.id);
    if (bytes == null) {
      return wire(MsgType.ASSET_FETCH_RESPONSE, session.sessionId, AssetBytes.notFound(descriptor.id));
    }
    if (fetch.rangeStart == null) {
      return wire(MsgType.ASSET_FETCH_RESPONSE, session.sessionId, AssetBytes.ok(descriptor.id, bytes));
    }
    long start = fetch.rangeStart;
    long len = fetch.rangeLen;
    if (start >= bytes.length) {
      return wire(
          MsgType.ASSET_FETCH_RESPONSE,
          session.sessionId,
          AssetBytes.error(descriptor.id, "RANGE_OUT_OF_BOUNDS", "rangeStart exceeds asset length")
      );
    }
    int from = (int) start;
    int to = (int) Math.min((long) bytes.length, start + len);
    byte[] chunk = Arrays.copyOfRange(bytes, from, to);
    return wire(
        MsgType.ASSET_FETCH_RESPONSE,
        session.sessionId,
        AssetBytes.okRange(descriptor.id, start, chunk.length, chunk)
    );
  }

  private WireMessage<?> onSendAction(Inbound inbound) {
    SessionState session = requireSession(inbound);
    ActionRequest action = ControlWire.bodyAs(inbound.wire, ActionRequest.class);
    PacketHandler<ActionRequest> handler = actionHandler.get();
    ActionResponse response;
    if (handler == null) {
      response = ActionResponse.rejected("NO_HANDLER", "No action handler");
    } else {
      try {
        handler.onPacket(action);
        response = ActionResponse.ok(Map.of("accepted", true));
      } catch (RuntimeException e) {
        response = ActionResponse.error("HANDLER_ERROR", e.getMessage());
      }
    }
    return wire(MsgType.ACTION_RESPONSE, session.sessionId, response);
  }

  private Inbound decodeInbound(byte[] requestBytes) {
    RuntimeException last = null;
    for (Codec codec : codecsInDecodeOrder()) {
      try {
        WireMessage<?> wire = ControlWire.decode(codec, requestBytes);
        if (wire.std.msgType == MsgType.AUTH) {
          return new Inbound(codec, wire);
        }
        SessionState session = sessions.get(wire.std.header.sessionId);
        if (session != null && session.codec == codec) {
          return new Inbound(codec, wire);
        }
        if (session == null) {
          return new Inbound(codec, wire);
        }
      } catch (RuntimeException e) {
        last = e;
      }
    }
    throw new IllegalArgumentException("Unable to decode inbound control payload", last);
  }

  private List<Codec> codecsInDecodeOrder() {
    return List.of(
        bootstrapCodec,
        codecsByName.get("cbor")
    );
  }

  private SessionState requireSession(Inbound inbound) {
    String sessionId = inbound.wire.std.header.sessionId;
    SessionState session = sessions.get(sessionId);
    if (session == null) {
      throw new MetaRayException("Unknown session: " + sessionId);
    }
    if (session.codec != inbound.codec) {
      throw new MetaRayException("Session codec mismatch for session: " + sessionId);
    }
    String inboundKeyId = inbound.wire.std.header.keyId;
    if (inboundKeyId == null || inboundKeyId.isBlank()) {
      throw new MetaRayException("Missing keyId for session: " + sessionId);
    }
    if (!session.receiverKeyId.equals(inboundKeyId)) {
      throw new MetaRayException("Session keyId mismatch for session: " + sessionId);
    }
    return session;
  }

  private Codec responseCodec(Inbound inbound, WireMessage<?> response) {
    if (response.std.msgType == MsgType.AUTH) {
      return inbound.codec;
    }
    if (response.std.msgType == MsgType.ERROR) {
      String sessionId = response.std.header.sessionId;
      if (sessionId == null) return inbound.codec;
      SessionState session = sessions.get(sessionId);
      if (session == null) return inbound.codec;
      return session.codec;
    }
    String sessionId = response.std.header.sessionId;
    if (sessionId == null) return inbound.codec;
    SessionState session = sessions.get(sessionId);
    return (session != null) ? session.codec : inbound.codec;
  }

  private WireMessage<?> wire(MsgType type, String sessionId, Object body) {
    return wire(type, sessionId, body, Long.toString(responseSeq.incrementAndGet()));
  }

  private WireMessage<?> wire(MsgType type, String sessionId, Object body, String headerId) {
    WireMessage.Header header = new WireMessage.Header(
        info.appId,
        headerId,
        info.deviceName,
        sessionId,
        null,
        null,
        null
    );
    return new WireMessage<>(
        new WireMessage.Std<>(1, type, header, body, null, null),
        Map.of()
    );
  }

  private WireMessage<ErrorMessage> errorWire(
      String sessionId,
      MsgType failedType,
      long failedSeq,
      String code,
      String message
  ) {
    Long seq = failedSeq >= 0 ? failedSeq : null;
    ErrorMessage error = new ErrorMessage(code, message != null ? message : code, Map.of(), failedType.wireName, seq);
    WireMessage.Header header = new WireMessage.Header(
        info.appId,
        Long.toString(responseSeq.incrementAndGet()),
        info.deviceName,
        sessionId,
        null,
        null,
        null
    );
    return new WireMessage<>(
        new WireMessage.Std<>(1, MsgType.ERROR, header, error, null, null),
        Map.of()
    );
  }

  private WireMessage<AuthMessage> authWire(AuthMessage body, boolean includeDeviceName) {
    return authWire(body, includeDeviceName, Map.of());
  }

  private WireMessage<AuthMessage> authWire(
      AuthMessage body,
      boolean includeDeviceName,
      Map<String, Object> ext
  ) {
    WireMessage.Header header = new WireMessage.Header(
        info.appId,
        info.emitterId,
        includeDeviceName ? info.deviceName : null,
        null,
        null,
        null,
        null
    );
    return new WireMessage<>(
        new WireMessage.Std<>(1, MsgType.AUTH, header, body, null, null),
        ext
    );
  }

  private String chooseRequestedCodec(java.util.List<String> requested) {
    for (String codec : requested) {
      if (codec == null || codec.isBlank()) {
        continue;
      }
      String normalized = codec.toLowerCase();
      if (emitterCodecs.contains(normalized) && codecsByName.containsKey(normalized)) {
        return normalized;
      }
    }
    return null;
  }

  private String codecNameFor(Codec codec) {
    if (codec == codecsByName.get("cbor")) return "cbor";
    return "json";
  }

  private String chooseRequestedSecurity(java.util.List<String> requested) {
    for (String security : requested) {
      if (security == null || security.isBlank()) {
        continue;
      }
      if (activeControlSecurity.equals(security.toLowerCase())) {
        return activeControlSecurity;
      }
    }
    return null;
  }

  private static List<String> extStringList(Map<String, Object> ext, String key) {
    Object raw = ext.get(key);
    if (raw instanceof List<?> list) {
      List<String> out = new java.util.ArrayList<>();
      for (Object value : list) {
        if (value instanceof String s && !s.isBlank()) {
          out.add(s);
        }
      }
      return List.copyOf(out);
    }
    return List.of();
  }

  private static String chooseControlSecurity(Set<String> emitterSecurity) {
    if (emitterSecurity.contains(SECURITY_TLS)) return SECURITY_TLS;
    throw new IllegalArgumentException("TLS is required for control security. Advertised set: " + emitterSecurity);
  }

  private static Set<String> normalizeLower(Set<String> values) {
    java.util.Set<String> out = new java.util.HashSet<>();
    for (String v : Checks.notNull(values, "values")) {
      out.add(Checks.notBlank(v, "value").toLowerCase());
    }
    return Set.copyOf(out);
  }

  private void ensureNotShutdown() {
    if (shutdown.get()) throw new MetaRayException("Emitter is shutdown");
  }

  private void ensureReadyForConnect() {
    ensureNotShutdown();
    if (!advertising.get()) throw new MetaRayException("Emitter is not advertising");
    if (!controlRunning.get()) throw new MetaRayException("Control server is not running");
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().withUpperCase().formatHex(d.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private String generateNonce() {
    byte[] nonce = new byte[32];
    secureRandom.nextBytes(nonce);
    return "base64url:" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
  }

  private byte[] generatePushGroupKey() {
    byte[] groupKey = new byte[PUSH_GROUP_KEY_BYTES];
    secureRandom.nextBytes(groupKey);
    return groupKey;
  }

  private static final class Inbound {
    final Codec codec;
    final WireMessage<?> wire;

    Inbound(Codec codec, WireMessage<?> wire) {
      this.codec = codec;
      this.wire = wire;
    }
  }

  private static final class SessionState {
    final String sessionId;
    final String codecName;
    final String security;
    final Codec codec;
    final String receiverKeyId;

    SessionState(
        String sessionId,
        String codecName,
        String security,
        Codec codec,
        String receiverKeyId
    ) {
      this.sessionId = sessionId;
      this.codecName = codecName;
      this.security = security;
      this.codec = codec;
      this.receiverKeyId = receiverKeyId;
    }
  }

  private static final class PendingChallenge {
    final String nonce;
    final long createdAtEpochMs;

    PendingChallenge(String nonce, long createdAtEpochMs) {
      this.nonce = nonce;
      this.createdAtEpochMs = createdAtEpochMs;
    }
  }

  private static final class PendingNegotiation {
    final String selectedCodec;
    final String selectedSecurity;

    PendingNegotiation(String selectedCodec, String selectedSecurity) {
      this.selectedCodec = Checks.notBlank(selectedCodec, "selectedCodec").toLowerCase();
      this.selectedSecurity = Checks.notBlank(selectedSecurity, "selectedSecurity").toLowerCase();
    }
  }

  private static final class ReceiverRegistryEntry {
    final String receiverKeyId;
    final String receiverId;
    final String receiverPublicKey;
    final long approvedAtEpochMs;
    volatile long lastSeenEpochMs;

    ReceiverRegistryEntry(
        String receiverKeyId,
        String receiverId,
        String receiverPublicKey,
        long approvedAtEpochMs,
        long lastSeenEpochMs
    ) {
      this.receiverKeyId = receiverKeyId;
      this.receiverId = receiverId;
      this.receiverPublicKey = receiverPublicKey;
      this.approvedAtEpochMs = approvedAtEpochMs;
      this.lastSeenEpochMs = lastSeenEpochMs;
    }
  }

  public enum PairingApprovalDecision {
    ACCEPT,
    DECLINE
  }

  @FunctionalInterface
  public interface PairingApprovalPolicy {
    PairingApprovalDecision decide(PairingApprovalRequest request);
  }

  public static final class PairingApprovalRequest {
    public final String receiverKeyId;
    public final String receiverId;
    public final String receiverPublicKey;
    public final String receiverDeviceName;

    PairingApprovalRequest(
        String receiverKeyId,
        String receiverId,
        String receiverPublicKey,
        String receiverDeviceName
    ) {
      this.receiverKeyId = Checks.notBlank(receiverKeyId, "receiverKeyId");
      this.receiverId = Checks.notBlank(receiverId, "receiverId");
      this.receiverPublicKey = Checks.notBlank(receiverPublicKey, "receiverPublicKey");
      this.receiverDeviceName = receiverDeviceName;
    }
  }
}
