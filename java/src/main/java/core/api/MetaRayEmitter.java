package core.api;

import core.model.*;
import core.spi.PacketHandler;
import java.util.concurrent.CompletableFuture;

public interface MetaRayEmitter {
  CompletableFuture<Void> startAdvertisingAsync();
  CompletableFuture<Void> stopAdvertisingAsync();

  CompletableFuture<Void> startControlServerAsync();
  CompletableFuture<Void> stopControlServerAsync();

  CompletableFuture<Void> pushEventAsync(ContextFrame eventFrame);

  CompletableFuture<AssetDescriptor> registerAssetAsync(AssetRegistration reg);

  CompletableFuture<Void> onActionAsync(PacketHandler<ActionRequest> handler);

  CompletableFuture<Void> shutdownAsync();

  default void startAdvertising() {
    MetaRayReceiver.await(startAdvertisingAsync());
  }

  default void stopAdvertising() {
    MetaRayReceiver.await(stopAdvertisingAsync());
  }

  default void startControlServer() {
    MetaRayReceiver.await(startControlServerAsync());
  }

  default void stopControlServer() {
    MetaRayReceiver.await(stopControlServerAsync());
  }

  default void pushEvent(ContextFrame eventFrame) {
    MetaRayReceiver.await(pushEventAsync(eventFrame));
  }

  default AssetDescriptor registerAsset(AssetRegistration reg) {
    return MetaRayReceiver.await(registerAssetAsync(reg));
  }

  default void onAction(PacketHandler<ActionRequest> handler) {
    MetaRayReceiver.await(onActionAsync(handler));
  }

  default void shutdown() {
    MetaRayReceiver.await(shutdownAsync());
  }
}
