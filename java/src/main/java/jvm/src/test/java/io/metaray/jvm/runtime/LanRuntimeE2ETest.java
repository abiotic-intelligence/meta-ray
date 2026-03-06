package io.metaray.jvm.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.api.EphemeralReceiverIdentityProvider;
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
import io.metaray.jvm.codec.JacksonCborCodec;
import io.metaray.jvm.codec.JacksonJsonCodec;
import io.metaray.jvm.discovery.ManualDiscovery;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LanRuntimeE2ETest {
  @Test
  void endToEndOverTlsControlChannel() throws Exception {
    int port = freePort();
    EmitterConfig info = new EmitterConfig(
        1,
        "emitter-e2e",
        "127.0.0.1",
        port,
        null,
        null,
        Set.of("json", "cbor"),
        Set.of("tls"),
        Set.of("assets", "actions")
    );

    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        info,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        null,
        TestTlsMaterial.serverSocketFactory(),
        null
    );
    emitter.startAdvertising();
    emitter.startControlServer();
    emitter.pushEvent(new ContextFrame(
        new ContextFrame.Std("media", false, null),
        Map.of()
    ));
    emitter.pushEvent(new ContextFrame(
        new ContextFrame.Std("offers", true, "evt-1"),
        Map.of("offerId", "1")
    ));
    emitter.pushEvent(new ContextFrame(
        new ContextFrame.Std("offers", true, "evt-2"),
        Map.of("offerId", "2")
    ));
    emitter.pushEvent(new ContextFrame(
        new ContextFrame.Std("offers", true, "evt-3"),
        Map.of("offerId", "3")
    ));

    AssetDescriptor asset = emitter.registerAsset(
        new AssetRegistration(AssetDescriptor.Type.IMAGE, new byte[] {1, 2, 3}, "a1", 1000L));

    AtomicBoolean actionHandled = new AtomicBoolean(false);
    emitter.onAction(req -> actionHandled.set(true));
    EphemeralReceiverIdentityProvider identity = new EphemeralReceiverIdentityProvider();
    emitter.registerReceiver(identity.currentKeyId(), identity.currentReceiverId(), identity.currentPublicKey());

    LanMetaRayReceiver receiver = new LanMetaRayReceiver(
        null,
        new ManualDiscovery(java.util.List.of(info)),
        new JacksonJsonCodec(),
        target -> TestTlsMaterial.newTransport(new InetSocketAddress(target.host, target.controlPort)),
        null,
        identity
    );

    assertTrue(!receiver.discover(Duration.ofMillis(10)).isEmpty());
    Session session = receiver.connect(info);
    assertNotNull(session);
    assertEquals("cbor", session.codec);
    assertEquals("tls", session.security);
    ContextFrame snapshot = receiver.pullContext("media", PullOptions.latest());
    assertNotNull(snapshot);
    assertEquals("media", snapshot.std.contextId);
    assertEquals(3, receiver.pullContext("offers", PullOptions.all()).size());
    java.util.List<ContextFrame> lastTwoOffers = receiver.pullContext("offers", PullOptions.limit(2));
    assertEquals(2, lastTwoOffers.size());
    assertEquals("evt-2", lastTwoOffers.get(0).std.eventId);
    assertEquals("evt-3", lastTwoOffers.get(1).std.eventId);

    AssetBytes bytes = receiver.fetchAsset(new AssetFetchRequest(asset.id, null, null, null));
    assertEquals(AssetBytes.Status.OK, bytes.status);
    assertEquals(3, bytes.bytes.length);

    ActionResponse response = receiver.sendAction(new ActionRequest("open", "idem-1", Map.of(), null));
    assertEquals(ActionResponse.Status.OK, response.status);
    assertTrue(actionHandled.get());

    receiver.close();
    emitter.shutdown();
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
