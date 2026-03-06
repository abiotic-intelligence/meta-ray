package core.api;

import core.model.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public interface MetaRayReceiver {
  CompletableFuture<List<EmitterConfig>> discoverAsync(Duration timeout);

  CompletableFuture<Session> connectAsync(EmitterConfig emitter);

  CompletableFuture<List<ContextFrame>> pullContextAsync(String contextId, PullOptions opts);

  CompletableFuture<ContextFrame> pullContextAsync(String contextId, PullOptions.Latest opts);

  CompletableFuture<AssetBytes> fetchAssetAsync(AssetFetchRequest req);

  CompletableFuture<ActionResponse> sendActionAsync(ActionRequest req);

  CompletableFuture<Void> closeAsync();

  default List<EmitterConfig> discover(Duration timeout) {
    return await(discoverAsync(timeout));
  }

  default Session connect(EmitterConfig emitter) {
    return await(connectAsync(emitter));
  }

  default List<ContextFrame> pullContext(String contextId, PullOptions opts) {
    return await(pullContextAsync(contextId, opts));
  }

  default ContextFrame pullContext(String contextId, PullOptions.Latest opts) {
    return await(pullContextAsync(contextId, opts));
  }

  default AssetBytes fetchAsset(AssetFetchRequest req) {
    return await(fetchAssetAsync(req));
  }

  default ActionResponse sendAction(ActionRequest req) {
    return await(sendActionAsync(req));
  }

  default void close() {
    await(closeAsync());
  }

  static <T> T await(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw e;
    }
  }
}
