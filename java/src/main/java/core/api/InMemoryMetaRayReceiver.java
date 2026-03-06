package core.api;

import core.model.ActionRequest;
import core.model.ActionResponse;
import core.model.AssetBytes;
import core.model.AssetFetchRequest;
import core.model.ContextFrame;
import core.model.EmitterConfig;
import core.model.PullOptions;
import core.model.Session;
import core.util.Checks;
import core.util.MetaRayException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryMetaRayReceiver implements MetaRayReceiver {
  private final InMemoryEmitterDirectory directory;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private volatile InMemoryMetaRayEmitter connectedEmitter;
  private volatile Session session;

  public InMemoryMetaRayReceiver(InMemoryEmitterDirectory directory) {
    this.directory = Checks.notNull(directory, "directory");
  }

  @Override
  public CompletableFuture<List<EmitterConfig>> discoverAsync(Duration timeout) {
    try {
      ensureOpen();
      return CompletableFuture.completedFuture(directory.discover(timeout));
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public synchronized CompletableFuture<Session> connectAsync(EmitterConfig emitter) {
    try {
      ensureOpen();
      Checks.notNull(emitter, "emitter");
      InMemoryMetaRayEmitter target = directory.resolve(emitter);
      Session opened = target.openSession();
      if (opened == null || opened.sessionId == null || opened.sessionId.isBlank()) {
        throw new MetaRayException("connect() returned invalid session");
      }
      connectedEmitter = target;
      session = opened;
      return CompletableFuture.completedFuture(opened);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<List<ContextFrame>> pullContextAsync(String contextId, PullOptions opts) {
    try {
      ensureConnected();
      List<ContextFrame> frames = connectedEmitter.pullContext(
          session.sessionId,
          Checks.notBlank(contextId, "contextId"),
          Checks.notNull(opts, "opts"));
      if (frames == null) {
        throw new MetaRayException("pullContext() returned null frames");
      }
      return CompletableFuture.completedFuture(frames);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<ContextFrame> pullContextAsync(String contextId, PullOptions.Latest opts) {
    try {
      ensureConnected();
      Checks.notNull(opts, "opts");
      List<ContextFrame> frames = connectedEmitter.pullContext(
          session.sessionId,
          Checks.notBlank(contextId, "contextId"),
          opts
      );
      if (frames == null) {
        throw new MetaRayException("pullContext() returned null frames");
      }
      ContextFrame latest = frames.isEmpty() ? null : frames.get(frames.size() - 1);
      return CompletableFuture.completedFuture(latest);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<AssetBytes> fetchAssetAsync(AssetFetchRequest req) {
    try {
      ensureConnected();
      AssetBytes bytes = connectedEmitter.fetchAsset(session.sessionId, Checks.notNull(req, "req"));
      if (bytes == null) {
        throw new MetaRayException("fetchAsset() returned null");
      }
      return CompletableFuture.completedFuture(bytes);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<ActionResponse> sendActionAsync(ActionRequest req) {
    try {
      ensureConnected();
      ActionResponse response = connectedEmitter.handleAction(session.sessionId, Checks.notNull(req, "req"));
      if (response == null) {
        throw new MetaRayException("sendAction() returned null");
      }
      return CompletableFuture.completedFuture(response);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public synchronized CompletableFuture<Void> closeAsync() {
    try {
      if (closed.compareAndSet(false, true)) {
        InMemoryMetaRayEmitter emitter = connectedEmitter;
        Session s = session;
        connectedEmitter = null;
        session = null;
        if (emitter != null && s != null) {
          emitter.closeSession(s.sessionId);
        }
      }
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new MetaRayException("Receiver is closed");
    }
  }

  private void ensureConnected() {
    ensureOpen();
    if (connectedEmitter == null || session == null) {
      throw new MetaRayException("Receiver is not connected");
    }
  }
}
