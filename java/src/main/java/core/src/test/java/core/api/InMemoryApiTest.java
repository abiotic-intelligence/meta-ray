package core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InMemoryApiTest {
  @Test
  void receiverEmitterFlow() {
    EmitterConfig info = new EmitterConfig(
        1,
        "em-1",
        "127.0.0.1",
        7001,
        "239.1.2.3",
        5001,
        Set.of("json", "cbor"),
        Set.of("tls"),
        Set.of("assets", "actions")
    );

    InMemoryMetaRayEmitter emitter = new InMemoryMetaRayEmitter(info);
    InMemoryEmitterDirectory directory = new InMemoryEmitterDirectory();
    directory.register(emitter);

    emitter.startAdvertising();
    emitter.startControlServer();
    ContextFrame eventFrame = new ContextFrame(
        new ContextFrame.Std("media", false, null),
        Map.of()
    );
    emitter.pushEvent(eventFrame);
    emitter.pushEvent(new ContextFrame(new ContextFrame.Std("offers", true, "evt-1"), Map.of("offerId", "1")));
    emitter.pushEvent(new ContextFrame(new ContextFrame.Std("offers", true, "evt-2"), Map.of("offerId", "2")));
    emitter.pushEvent(new ContextFrame(new ContextFrame.Std("offers", true, "evt-3"), Map.of("offerId", "3")));

    InMemoryMetaRayReceiver receiver = new InMemoryMetaRayReceiver(directory);
    assertFalse(receiver.discover(Duration.ofMillis(50)).isEmpty());

    Session session = receiver.connect(info);
    assertNotNull(session);
    assertNotNull(session.sessionId);

    ContextFrame pulled = receiver.pullContext("media", PullOptions.latest());
    assertNotNull(pulled);
    assertEquals("media", pulled.std.contextId);

    java.util.List<ContextFrame> allOffers = receiver.pullContext("offers", PullOptions.all());
    assertEquals(3, allOffers.size());
    ContextFrame latestOffer = receiver.pullContext("offers", PullOptions.latest());
    assertNotNull(latestOffer);
    assertEquals("evt-3", latestOffer.std.eventId);
    java.util.List<ContextFrame> lastTwo = receiver.pullContext("offers", PullOptions.limit(2));
    assertEquals(2, lastTwo.size());
    assertEquals("evt-2", lastTwo.get(0).std.eventId);
    assertEquals("evt-3", lastTwo.get(1).std.eventId);

    AssetDescriptor d = emitter.registerAsset(
        new AssetRegistration(AssetDescriptor.Type.IMAGE, new byte[] {1, 2, 3, 4}, "asset-x", 10_000L));
    AssetBytes bytes = receiver.fetchAsset(new AssetFetchRequest(d.id, null, null, null));
    assertEquals(AssetBytes.Status.OK, bytes.status);
    assertEquals(4, bytes.bytes.length);

    AtomicBoolean actionHandled = new AtomicBoolean(false);
    emitter.onAction(req -> actionHandled.set(true));
    ActionResponse actionResponse = receiver.sendAction(new ActionRequest("open", "idemp-1", Map.of(), null));
    assertEquals(ActionResponse.Status.OK, actionResponse.status);
    assertTrue(actionHandled.get());

    receiver.close();
    receiver.close(); // idempotent
    emitter.shutdown();
    emitter.shutdown(); // idempotent
  }
}
