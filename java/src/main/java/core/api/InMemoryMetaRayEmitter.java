package core.api;

import core.model.ActionRequest;
import core.model.ActionResponse;
import core.model.AssetBytes;
import core.model.AssetDescriptor;
import core.model.AssetFetchRequest;
import core.model.AssetRegistration;
import core.model.ContextFrame;
import core.model.EmitterConfig;
import core.model.PullOptions;
import core.model.Session;
import core.spi.PacketHandler;
import core.util.Checks;
import core.util.MetaRayException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryMetaRayEmitter implements MetaRayEmitter {
  private final EmitterConfig info;
  private final AtomicBoolean advertising = new AtomicBoolean(false);
  private final AtomicBoolean controlServerRunning = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private final AtomicLong assetSeq = new AtomicLong(0);

  private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AssetDescriptor> assetById = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AssetDescriptor> assetByHash = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, byte[]> assetBytesById = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, CopyOnWriteArrayList<ContextFrame>> framesByContextId = new ConcurrentHashMap<>();
  private final AtomicReference<PacketHandler<ActionRequest>> actionHandler = new AtomicReference<>();

  public InMemoryMetaRayEmitter(EmitterConfig info) {
    this.info = Checks.notNull(info, "info");
  }

  public EmitterConfig info() {
    return info;
  }

  boolean isAdvertising() {
    return advertising.get();
  }

  @Override
  public CompletableFuture<Void> startAdvertisingAsync() {
    try {
      ensureNotShutdown();
      advertising.set(true);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> stopAdvertisingAsync() {
    try {
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
      controlServerRunning.set(true);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> stopControlServerAsync() {
    try {
      controlServerRunning.set(false);
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
      framesByContextId.computeIfAbsent(event.std.contextId, ignored -> new CopyOnWriteArrayList<>()).add(event);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<AssetDescriptor> registerAssetAsync(AssetRegistration reg) {
    try {
      ensureNotShutdown();
      AssetRegistration registration = Checks.notNull(reg, "registration");
      String hash = sha256(registration.bytes);
      String id = registration.suggestedId != null
          ? registration.suggestedId
          : "asset-" + assetSeq.incrementAndGet();

      AssetDescriptor descriptor = new AssetDescriptor(
          id,
          registration.type,
          hash,
          (long) registration.bytes.length,
          "/asset/" + id,
          registration.ttlMs
      );
      assetById.put(descriptor.id, descriptor);
      assetByHash.put(descriptor.hash, descriptor);
      assetBytesById.put(descriptor.id, registration.bytes.clone());
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
        advertising.set(false);
        controlServerRunning.set(false);
        sessions.clear();
      }
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  Session openSession() {
    ensureControlReady();
    String sessionId = UUID.randomUUID().toString();
    SessionState state = new SessionState(new Session(sessionId));
    sessions.put(sessionId, state);
    return state.session;
  }

  void closeSession(String sessionId) {
    if (sessionId == null) return;
    sessions.remove(sessionId);
  }

  java.util.List<ContextFrame> pullContext(String sessionId, String contextId, PullOptions options) {
    requireSession(sessionId);
    String id = Checks.notBlank(contextId, "contextId");
    PullOptions opts = Checks.notNull(options, "options");
    java.util.List<ContextFrame> frames = framesByContextId.get(id);
    if (frames == null || frames.isEmpty()) {
      return java.util.List.of();
    }
    return selectFrames(frames, opts);
  }

  AssetBytes fetchAsset(String sessionId, AssetFetchRequest request) {
    requireSession(sessionId);
    AssetFetchRequest req = Checks.notNull(request, "request");
    AssetDescriptor descriptor = req.assetId != null ? assetById.get(req.assetId) : assetByHash.get(req.hash);
    if (descriptor == null) {
      return AssetBytes.notFound(req.assetId != null ? req.assetId : req.hash);
    }
    byte[] full = assetBytesById.get(descriptor.id);
    if (full == null) {
      return AssetBytes.notFound(descriptor.id);
    }

    if (req.rangeStart == null) {
      return AssetBytes.ok(descriptor.id, full);
    }
    long start = req.rangeStart;
    long len = req.rangeLen;
    if (start >= full.length) {
      return AssetBytes.error(descriptor.id, "RANGE_OUT_OF_BOUNDS", "rangeStart exceeds asset length");
    }
    int from = (int) start;
    int to = (int) Math.min((long) full.length, start + len);
    if (to <= from) {
      return AssetBytes.error(descriptor.id, "RANGE_INVALID", "Requested range is empty");
    }
    byte[] chunk = java.util.Arrays.copyOfRange(full, from, to);
    return AssetBytes.okRange(descriptor.id, start, chunk.length, chunk);
  }

  ActionResponse handleAction(String sessionId, ActionRequest request) {
    requireSession(sessionId);
    ActionRequest req = Checks.notNull(request, "request");
    PacketHandler<ActionRequest> handler = actionHandler.get();
    if (handler == null) {
      throw new MetaRayException("No action handler registered");
    }
    try {
      handler.onPacket(req);
      return ActionResponse.ok(Map.of("accepted", true));
    } catch (RuntimeException e) {
      throw new MetaRayException("Action handler failed", e);
    }
  }

  private SessionState requireSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    SessionState state = sessions.get(sessionId);
    if (state == null) {
      throw new MetaRayException("Unknown session: " + sessionId);
    }
    return state;
  }

  private void ensureNotShutdown() {
    if (shutdown.get()) {
      throw new MetaRayException("Emitter is shutdown");
    }
  }

  private void ensureControlReady() {
    ensureNotShutdown();
    if (!advertising.get()) {
      throw new MetaRayException("Emitter is not advertising");
    }
    if (!controlServerRunning.get()) {
      throw new MetaRayException("Control server is not running");
    }
  }

  private static String sha256(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      byte[] hash = d.digest(bytes);
      return "sha256:" + HexFormat.of().withUpperCase().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static java.util.List<ContextFrame> selectFrames(java.util.List<ContextFrame> source, PullOptions opts) {
    if (opts.mode == PullOptions.Mode.ALL) {
      return java.util.List.copyOf(source);
    }
    if (opts.mode == PullOptions.Mode.LATEST) {
      return java.util.List.of(source.get(source.size() - 1));
    }
    int size = source.size();
    int from = Math.max(0, size - opts.limit);
    return java.util.List.copyOf(source.subList(from, size));
  }

  private static final class SessionState {
    final Session session;

    SessionState(Session session) {
      this.session = session;
    }
  }
}
