package io.metaray.android.runtime;

import android.content.Context;
import core.api.EphemeralReceiverIdentityProvider;
import core.api.MetaRayReceiver;
import core.api.ReceiverIdentity;
import core.api.ReceiverIdentityProvider;
import core.model.ActionRequest;
import core.model.ActionResponse;
import core.model.AssetBytes;
import core.model.AssetFetchRequest;
import core.model.ContextFrame;
import core.model.EmitterConfig;
import core.model.PullOptions;
import core.model.Session;
import core.model.wire.AuthProof;
import core.model.wire.AuthMessage;
import core.model.wire.ErrorMessage;
import core.model.wire.MsgType;
import core.model.wire.PullMessage;
import core.model.wire.WireMessage;
import core.spi.Codec;
import core.spi.ControlTransport;
import core.spi.CryptoProvider;
import core.spi.MulticastTransport;
import core.util.Checks;
import core.util.MetaRayException;
import io.metaray.android.config.AndroidTransportConfig;
import io.metaray.android.codec.JacksonCborCodec;
import io.metaray.android.codec.JacksonJsonCodec;
import io.metaray.android.crypto.AesGcmCryptoProvider;
import io.metaray.android.discovery.ManualDiscovery;
import io.metaray.android.discovery.NsdDiscovery;
import io.metaray.android.net.WifiMulticastLockManager;
import io.metaray.android.transport.TlsControlTransport;
import io.metaray.android.transport.UdpMulticastTransport;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class LanMetaRayReceiver implements MetaRayReceiver {
  private static final String SECURITY_TLS = "tls";
  private static final String EXT_NEGOTIATION_SUPPORTED_CODECS = "metaray.negotiation.supportedCodecs";
  private static final String EXT_NEGOTIATION_SUPPORTED_SECURITY = "metaray.negotiation.supportedSecurity";
  private static final String EXT_NEGOTIATION_SELECTED_CODEC = "metaray.negotiation.selectedCodec";
  private static final String EXT_NEGOTIATION_SELECTED_SECURITY = "metaray.negotiation.selectedSecurity";
  private static final String EXT_PUSH_GROUP_KEY = "metaray.push.groupKey";
  private static final String EXT_PUSH_CIPHER = "metaray.push.cipher";
  private static final String PUSH_CIPHER_AES_GCM = "aes-gcm";
  private static final PairingConsentHandler DEFAULT_PAIRING_CONSENT = emitter -> false;

  @FunctionalInterface
  public interface PairingConsentHandler {
    boolean onPairRequired(EmitterConfig emitter);
  }

  private final NsdDiscovery discovery;
  private final ManualDiscovery manualFallback;
  private final JacksonJsonCodec bootstrapCodec;
  private final Map<String, Codec> codecsByName;
  private final List<String> supportedCodecs;
  private final List<String> supportedSecurity;
  private final Function<EmitterConfig, ControlTransport> controlFactory;
  private final WifiMulticastLockManager multicastLockManager;
  private final ReceiverIdentityProvider receiverIdentityProvider;
  private final PairingConsentHandler pairingConsentHandler;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicLong requestSeq = new AtomicLong(0);

  private volatile EmitterConfig connectedEmitter;
  private volatile Session session;
  private volatile ControlTransport control;
  private volatile MulticastTransport multicast;
  private volatile Codec sessionCodec;
  private volatile String sessionSecurity;
  private volatile String sessionKeyId;

  public LanMetaRayReceiver(Context context) {
    this(new NsdDiscovery(context), null, new JacksonJsonCodec(), null, new EphemeralReceiverIdentityProvider(),
        defaultMulticastLockManager(context),
        DEFAULT_PAIRING_CONSENT);
  }

  public LanMetaRayReceiver(Context context, String appId) {
    this(
        new NsdDiscovery(context, NsdDiscovery.DEFAULT_SERVICE_TYPE, appId),
        null,
        new JacksonJsonCodec(),
        null,
        new EphemeralReceiverIdentityProvider(),
        defaultMulticastLockManager(context),
        DEFAULT_PAIRING_CONSENT
    );
  }

  public LanMetaRayReceiver(
      NsdDiscovery discovery,
      ManualDiscovery manualFallback,
      JacksonJsonCodec codec,
      Function<EmitterConfig, ControlTransport> controlFactory
  ) {
    this(discovery, manualFallback, codec, controlFactory, new EphemeralReceiverIdentityProvider(),
        null,
        DEFAULT_PAIRING_CONSENT);
  }

  public LanMetaRayReceiver(
      NsdDiscovery discovery,
      ManualDiscovery manualFallback,
      JacksonJsonCodec codec,
      Function<EmitterConfig, ControlTransport> controlFactory,
      ReceiverIdentityProvider receiverIdentityProvider
  ) {
    this(
        discovery,
        manualFallback,
        codec,
        controlFactory,
        receiverIdentityProvider,
        null,
        DEFAULT_PAIRING_CONSENT
    );
  }

  public LanMetaRayReceiver(
      NsdDiscovery discovery,
      ManualDiscovery manualFallback,
      JacksonJsonCodec codec,
      Function<EmitterConfig, ControlTransport> controlFactory,
      ReceiverIdentityProvider receiverIdentityProvider,
      PairingConsentHandler pairingConsentHandler
  ) {
    this(
        discovery,
        manualFallback,
        codec,
        controlFactory,
        receiverIdentityProvider,
        null,
        pairingConsentHandler
    );
  }

  public LanMetaRayReceiver(
      NsdDiscovery discovery,
      ManualDiscovery manualFallback,
      JacksonJsonCodec codec,
      Function<EmitterConfig, ControlTransport> controlFactory,
      ReceiverIdentityProvider receiverIdentityProvider,
      WifiMulticastLockManager multicastLockManager,
      PairingConsentHandler pairingConsentHandler
  ) {
    this.discovery = Checks.notNull(discovery, "discovery");
    this.manualFallback = manualFallback;
    this.bootstrapCodec = Checks.notNull(codec, "codec");
    Map<String, Codec> codecMap = new HashMap<>();
    codecMap.put("json", bootstrapCodec);
    codecMap.put("cbor", new JacksonCborCodec());
    this.codecsByName = Map.copyOf(codecMap);
    this.supportedCodecs = List.of("cbor", "json");
    this.supportedSecurity = List.of("tls");
    this.controlFactory = controlFactory;
    this.multicastLockManager = multicastLockManager;
    this.receiverIdentityProvider = Checks.notNull(receiverIdentityProvider, "receiverIdentityProvider");
    this.pairingConsentHandler = Checks.notNull(pairingConsentHandler, "pairingConsentHandler");
  }

  @Override
  public CompletableFuture<List<EmitterConfig>> discoverAsync(Duration timeout) {
    try {
      ensureOpen();
      List<EmitterConfig> out = new ArrayList<>(discovery.discover(timeout));
      if (out.isEmpty() && manualFallback != null) {
        out.addAll(manualFallback.discover(timeout));
      }
      return CompletableFuture.completedFuture(out);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public synchronized CompletableFuture<Session> connectAsync(EmitterConfig emitter) {
    try {
      ensureOpen();
      EmitterConfig target = Checks.notNull(emitter, "emitter");
      List<String> candidateSecurity = intersectionOrdered(supportedSecurity, normalizeLower(target.security));
      if (candidateSecurity.isEmpty()) {
        return CompletableFuture.failedFuture(
            new MetaRayException("No compatible control security with emitter: " + target.security)
        );
      }
      return connectForSecurity(target, candidateSecurity, 0, null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<List<ContextFrame>> pullContextAsync(String contextId, PullOptions opts) {
    ensureConnected();
    PullOptions options = Checks.notNull(opts, "opts");
    return requestAsync(
        MsgType.PULL,
        new PullMessage(Checks.notBlank(contextId, "contextId"), options.mode, options.limit, List.of()),
        MsgType.PULL,
        PullMessage.class,
        Duration.ofSeconds(3)
    ).thenApply(response -> {
      return response.frames;
    });
  }

  @Override
  public CompletableFuture<ContextFrame> pullContextAsync(String contextId, PullOptions.Latest opts) {
    ensureConnected();
    PullOptions.Latest options = Checks.notNull(opts, "opts");
    return requestAsync(
        MsgType.PULL,
        new PullMessage(Checks.notBlank(contextId, "contextId"), options.mode, options.limit, List.of()),
        MsgType.PULL,
        PullMessage.class,
        Duration.ofSeconds(3)
    ).thenApply(response -> {
      return response.frames.isEmpty() ? null : response.frames.get(response.frames.size() - 1);
    });
  }

  @Override
  public CompletableFuture<AssetBytes> fetchAssetAsync(AssetFetchRequest req) {
    ensureConnected();
    return requestAsync(
        MsgType.ASSET_FETCH_REQUEST,
        Checks.notNull(req, "req"),
        MsgType.ASSET_FETCH_RESPONSE,
        AssetBytes.class,
        Duration.ofSeconds(5)
    );
  }

  @Override
  public CompletableFuture<ActionResponse> sendActionAsync(ActionRequest req) {
    ensureConnected();
    return requestAsync(
        MsgType.ACTION_REQUEST,
        Checks.notNull(req, "req"),
        MsgType.ACTION_RESPONSE,
        ActionResponse.class,
        Duration.ofSeconds(5)
    );
  }

  @Override
  public synchronized CompletableFuture<Void> closeAsync() {
    try {
      if (closed.compareAndSet(false, true)) {
        connectedEmitter = null;
        session = null;
        sessionCodec = null;
        sessionSecurity = null;
        sessionKeyId = null;
        ControlTransport tx = control;
        control = null;
        if (tx != null) tx.close();
        MulticastTransport m = multicast;
        multicast = null;
        if (m != null) m.close();
      }
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private CompletableFuture<Session> connectForSecurity(
      EmitterConfig target,
      List<String> candidateSecurity,
      int index,
      RuntimeException lastFailure
  ) {
    if (index >= candidateSecurity.size()) {
      return CompletableFuture.failedFuture(new MetaRayException("Connect failed for emitter " + target.instanceId, lastFailure));
    }
    String security = candidateSecurity.get(index);
    final ReceiverIdentity identity;
    final ControlTransport candidateTransport;
    try {
      identity = receiverIdentityProvider.currentIdentity();
      candidateTransport = (controlFactory != null)
          ? controlFactory.apply(target)
          : createTransportForSecurity(target, security);
    } catch (RuntimeException e) {
      if (controlFactory != null || index + 1 >= candidateSecurity.size()) {
        return CompletableFuture.failedFuture(new MetaRayException("Connect failed for emitter " + target.instanceId, e));
      }
      return connectForSecurity(target, candidateSecurity, index + 1, e);
    }

    return performAuthHandshakeAsync(candidateTransport, target, identity)
        .thenApply(auth -> {
          if (!security.equals(auth.security)) {
            throw new MetaRayException(
                "Negotiated control security '" + auth.security + "' does not match active transport '" + security + "'"
            );
          }
          Session opened = new Session(auth.sessionId, auth.codec, auth.security);
          Codec negotiatedCodec = codecByName(auth.codec);
          MulticastTransport multicastTransport = null;
          try {
            multicastTransport = createMulticastListener(target, auth.pushCrypto);
            synchronized (this) {
              this.sessionCodec = negotiatedCodec;
              this.sessionSecurity = auth.security;
              this.sessionKeyId = identity.keyId;
              this.control = candidateTransport;
              this.connectedEmitter = target;
              this.session = opened;
              MulticastTransport previousMulticast = this.multicast;
              this.multicast = multicastTransport;
              if (previousMulticast != null) {
                previousMulticast.close();
              }
            }
          } catch (RuntimeException e) {
            if (multicastTransport != null) {
              try {
                multicastTransport.close();
              } catch (RuntimeException ignored) {
                // keep original failure
              }
            }
            throw e;
          }
          return opened;
        })
        .handle((opened, failure) -> {
          if (failure == null) return CompletableFuture.<Session>completedFuture(opened);
          try {
            candidateTransport.close();
          } catch (RuntimeException ignored) {
            // keep original failure
          }
          RuntimeException runtimeFailure = runtimeFailure(failure);
          if (controlFactory != null || index + 1 >= candidateSecurity.size()) {
            return CompletableFuture.<Session>failedFuture(
                new MetaRayException("Connect failed for emitter " + target.instanceId, runtimeFailure)
            );
          }
          return connectForSecurity(target, candidateSecurity, index + 1, runtimeFailure);
        })
        .thenCompose(next -> next);
  }

  private CompletableFuture<AuthHandshakeResult> performAuthHandshakeAsync(
      ControlTransport tx,
      EmitterConfig target,
      ReceiverIdentity identity
  ) {
    WireMessage.Header helloHeader = new WireMessage.Header(
        target.appId,
        identity.id,
        null,
        null,
        identity.keyId,
        identity.publicKey,
        null
    );
    WireMessage<AuthMessage> hello = new WireMessage<>(
        new WireMessage.Std<>(1, MsgType.AUTH, helloHeader, AuthMessage.hello(), null, null),
        negotiationExt()
    );

    return requestAuthAsync(tx, hello, "hello")
        .thenCompose(firstResponse -> {
          if ("challenge".equals(firstResponse.message.step)) {
            return CompletableFuture.completedFuture(firstResponse.message);
          }
          if ("pairRequired".equals(firstResponse.message.step)) {
            if (!pairingConsentHandler.onPairRequired(target)) {
              return CompletableFuture.failedFuture(
                  new MetaRayException("Pairing declined by user for receiver key: " + identity.keyId)
              );
            }
            WireMessage.Header pairHeader = new WireMessage.Header(
                target.appId,
                identity.id,
                null,
                null,
                identity.keyId,
                identity.publicKey,
                null
            );
            WireMessage<AuthMessage> pair = new WireMessage<>(
                new WireMessage.Std<>(1, MsgType.AUTH, pairHeader, AuthMessage.pair(), null, null),
                negotiationExt()
            );
            return requestAuthAsync(tx, pair, "pair").thenApply(pairResponse -> {
              if ("deny".equals(pairResponse.message.step)) {
                throw new MetaRayException("Pair denied: " + pairResponse.message.reason);
              }
              if (!"challenge".equals(pairResponse.message.step)) {
                throw new MetaRayException("Unsupported auth response step after pair: " + pairResponse.message.step);
              }
              return pairResponse.message;
            });
          }
          if ("deny".equals(firstResponse.message.step)) {
            return CompletableFuture.failedFuture(new MetaRayException("Auth denied: " + firstResponse.message.reason));
          }
          return CompletableFuture.failedFuture(
              new MetaRayException("Unsupported auth response step: " + firstResponse.message.step)
          );
        })
        .thenCompose(challenge -> {
          if (challenge.nonce == null || challenge.nonce.isBlank()) {
            return CompletableFuture.failedFuture(new MetaRayException("Auth challenge missing nonce"));
          }
          byte[] payload = AuthProof.canonicalPayload(target.emitterId, identity.keyId, challenge.nonce);
          String proof = AuthProof.encodeBase64Url(receiverIdentityProvider.signAuthPayload(payload));
          WireMessage.Header proofHeader = new WireMessage.Header(
              target.appId,
              identity.id,
              null,
              null,
              identity.keyId,
              null,
              null
          );
          WireMessage<AuthMessage> proofMessage = new WireMessage<>(
              new WireMessage.Std<>(1, MsgType.AUTH, proofHeader, AuthMessage.proof(challenge.nonce, proof), null, null),
              Map.of()
          );
          return requestAuthAsync(tx, proofMessage, "proof").thenApply(proofResponse -> {
            if ("ok".equals(proofResponse.message.step)) {
              if (proofResponse.message.sessionId == null || proofResponse.message.sessionId.isBlank()) {
                throw new MetaRayException("Auth ok missing sessionId");
              }
              return new AuthHandshakeResult(
                  proofResponse.message.sessionId,
                  selectedCodecFromAuthExt(proofResponse.ext),
                  selectedSecurityFromAuthExt(proofResponse.ext),
                  buildPushCryptoFromAuthExt(proofResponse.ext)
              );
            }
            if ("deny".equals(proofResponse.message.step)) {
              throw new MetaRayException("Proof denied: " + proofResponse.message.reason);
            }
            throw new MetaRayException("Unsupported auth response step after proof: " + proofResponse.message.step);
          });
        });
  }

  private AuthResponse validateAuthResponse(byte[] responseBytes, String op) {
    WireMessage<?> response = ControlWire.decode(bootstrapCodec, responseBytes);
    if (response.std.msgType == MsgType.ERROR) {
      ErrorMessage error = ControlWire.bodyAs(response, ErrorMessage.class);
      throw new MetaRayException("Auth " + op + " failed: " + error.errorCode + " " + error.errorMsg);
    }
    if (response.std.msgType != MsgType.AUTH) {
      throw new MetaRayException("Unexpected auth response type: " + response.std.msgType);
    }
    return new AuthResponse(ControlWire.bodyAs(response, AuthMessage.class), response.ext);
  }

  private CompletableFuture<AuthResponse> requestAuthAsync(ControlTransport tx, WireMessage<AuthMessage> request, String op) {
    final byte[] requestBytes;
    try {
      requestBytes = bootstrapCodec.encode(request);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return tx.request(requestBytes, Duration.ofSeconds(3))
        .thenApply(responseBytes -> validateAuthResponse(responseBytes, op));
  }

  private <TReq, TResp> CompletableFuture<TResp> requestAsync(
      MsgType requestType,
      TReq requestBody,
      MsgType expectedResponseType,
      Class<TResp> responseBodyType,
      Duration timeout
  ) {
    return requestWireAsync(requestType, requestBody, timeout).thenApply(response -> {
      if (response.std.msgType == MsgType.ERROR) {
        ErrorMessage error = ControlWire.bodyAs(response, ErrorMessage.class);
        throw new MetaRayException(error.errorCode + ": " + error.errorMsg);
      }
      if (response.std.msgType != expectedResponseType) {
        throw new MetaRayException("Unexpected response type: expected=" + expectedResponseType
            + " actual=" + response.std.msgType);
      }
      return ControlWire.bodyAs(response, responseBodyType);
    });
  }

  private CompletableFuture<WireMessage<?>> requestWireAsync(MsgType requestType, Object requestBody, Duration timeout) {
    Codec codec = Checks.notNull(sessionCodec, "sessionCodec");
    Session activeSession = Checks.notNull(session, "session");
    String activeKeyId = Checks.notBlank(sessionKeyId, "sessionKeyId");
    ControlTransport activeControl = Checks.notNull(control, "control");
    byte[] requestBytes = ControlWire.encode(
        codec,
        requestType,
        activeSession.sessionId,
        activeKeyId,
        requestSeq.incrementAndGet(),
        requestBody
    );
    return activeControl.request(requestBytes, timeout).thenApply(responseBytes -> ControlWire.decode(codec, responseBytes));
  }

  private static RuntimeException runtimeFailure(Throwable failure) {
    Throwable cause = failure;
    while (cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) {
      if (cause.getCause() == null) break;
      cause = cause.getCause();
    }
    if (cause instanceof RuntimeException runtime) return runtime;
    if (cause instanceof Error error) throw error;
    return new MetaRayException("Unexpected async failure", cause);
  }

  private ControlTransport createTransportForSecurity(EmitterConfig info, String security) {
    InetSocketAddress endpoint = new InetSocketAddress(info.host, info.controlPort);
    if (SECURITY_TLS.equals(security)) {
      return new TlsControlTransport(endpoint);
    }
    throw new MetaRayException("Unsupported control security: " + security);
  }

  private CryptoProvider buildPushCryptoFromAuthExt(Map<String, Object> ext) {
    Object cipher = ext.get(EXT_PUSH_CIPHER);
    if (!(cipher instanceof String) || !PUSH_CIPHER_AES_GCM.equalsIgnoreCase((String) cipher)) {
      throw new MetaRayException("Unsupported push cipher negotiated: " + cipher);
    }
    Object encodedGroupKey = ext.get(EXT_PUSH_GROUP_KEY);
    if (!(encodedGroupKey instanceof String)) {
      throw new MetaRayException("Auth ok missing push group key");
    }
    String encoded = (String) encodedGroupKey;
    if (encoded.isBlank()) {
      throw new MetaRayException("Auth ok missing push group key");
    }
    byte[] groupKey;
    try {
      groupKey = Base64.getUrlDecoder().decode(encoded);
    } catch (IllegalArgumentException invalidKey) {
      throw new MetaRayException("Invalid push group key encoding", invalidKey);
    }
    return new AesGcmCryptoProvider(groupKey);
  }

  private Map<String, Object> negotiationExt() {
    return Map.of(
        EXT_NEGOTIATION_SUPPORTED_CODECS, supportedCodecs,
        EXT_NEGOTIATION_SUPPORTED_SECURITY, supportedSecurity
    );
  }

  private String selectedCodecFromAuthExt(Map<String, Object> ext) {
    String codec = extString(ext, EXT_NEGOTIATION_SELECTED_CODEC);
    if (!codecsByName.containsKey(codec)) {
      throw new MetaRayException("Auth ok selected unsupported codec: " + codec);
    }
    return codec;
  }

  private String selectedSecurityFromAuthExt(Map<String, Object> ext) {
    String security = extString(ext, EXT_NEGOTIATION_SELECTED_SECURITY);
    if (!supportedSecurity.contains(security)) {
      throw new MetaRayException("Auth ok selected unsupported security: " + security);
    }
    return security;
  }

  private Codec codecByName(String codecName) {
    Codec codec = codecsByName.get(codecName);
    if (codec == null) {
      throw new MetaRayException("Unsupported negotiated codec: " + codecName);
    }
    return codec;
  }

  private static String extString(Map<String, Object> ext, String key) {
    Object raw = ext.get(key);
    if (!(raw instanceof String value) || value.isBlank()) {
      throw new MetaRayException("Auth ok missing negotiation extension: " + key);
    }
    return value.toLowerCase();
  }

  private MulticastTransport createMulticastListener(EmitterConfig emitter, CryptoProvider pushCrypto) {
    if (emitter.multicastGroup == null || emitter.multicastPort == null) {
      return null;
    }
    UdpMulticastTransport transport = new UdpMulticastTransport(AndroidTransportConfig.defaults(), multicastLockManager);
    transport.join(new InetSocketAddress(emitter.multicastGroup, emitter.multicastPort));
    return transport;
  }

  private static List<String> normalizeLower(Set<String> values) {
    List<String> out = new ArrayList<>();
    for (String v : values) {
      out.add(v.toLowerCase());
    }
    return out;
  }

  private static List<String> intersectionOrdered(List<String> preferred, List<String> available) {
    Set<String> availableSet = new LinkedHashSet<>(available);
    List<String> out = new ArrayList<>();
    for (String value : preferred) {
      if (availableSet.contains(value.toLowerCase())) {
        out.add(value.toLowerCase());
      }
    }
    return out;
  }

  private static WifiMulticastLockManager defaultMulticastLockManager(Context context) {
    return new WifiMulticastLockManager(Checks.notNull(context, "context"), "metaray-android-multicast");
  }

  private void ensureOpen() {
    if (closed.get()) throw new MetaRayException("Receiver is closed");
  }

  private void ensureConnected() {
    ensureOpen();
    if (session == null || control == null || sessionCodec == null
        || sessionSecurity == null || sessionKeyId == null) {
      throw new MetaRayException("Receiver is not connected");
    }
  }

  private static final class AuthResponse {
    final AuthMessage message;
    final Map<String, Object> ext;

    AuthResponse(AuthMessage message, Map<String, Object> ext) {
      this.message = Checks.notNull(message, "message");
      this.ext = (ext == null) ? Map.of() : ext;
    }
  }

  private static final class AuthHandshakeResult {
    final String sessionId;
    final String codec;
    final String security;
    final CryptoProvider pushCrypto;

    AuthHandshakeResult(String sessionId, String codec, String security, CryptoProvider pushCrypto) {
      this.sessionId = Checks.notBlank(sessionId, "sessionId");
      this.codec = Checks.notBlank(codec, "codec").toLowerCase();
      this.security = Checks.notBlank(security, "security").toLowerCase();
      this.pushCrypto = Checks.notNull(pushCrypto, "pushCrypto");
    }
  }
}
