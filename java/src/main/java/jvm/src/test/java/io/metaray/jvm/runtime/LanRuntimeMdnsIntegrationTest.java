package io.metaray.jvm.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.metaray.jvm.codec.JacksonCborCodec;
import io.metaray.jvm.codec.JacksonJsonCodec;
import io.metaray.jvm.discovery.JmDnsAdvertiser;
import io.metaray.jvm.discovery.JmDnsDiscovery;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jmdns.JmDNS;
import org.junit.jupiter.api.Test;

class LanRuntimeMdnsIntegrationTest {
  @Test
  void endToEndViaMdnsDiscovery() throws Exception {
    int controlPort = freePort();
    String instanceId = "emitter-mdns-" + UUID.randomUUID();
    EmitterConfig emitterConfig = new EmitterConfig(
        1,
        instanceId,
        "127.0.0.1",
        controlPort,
        null,
        null,
        Set.of("json", "cbor"),
        Set.of("tls"),
        Set.of("assets", "actions")
    );

    JmDNS advertiserMdns = JmDNS.create(InetAddress.getLocalHost());
    JmDNS discoveryMdns = JmDNS.create(InetAddress.getLocalHost());
    JmDnsAdvertiser advertiser = new JmDnsAdvertiser(advertiserMdns);
    JmDnsDiscovery discovery = new JmDnsDiscovery(discoveryMdns, JmDnsDiscovery.DEFAULT_CONTROL_SERVICE_TYPE);

    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        emitterConfig,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        advertiser,
        TestTlsMaterial.serverSocketFactory(),
        null
    );
    EphemeralReceiverIdentityProvider identity = new EphemeralReceiverIdentityProvider();
    emitter.registerReceiver(identity.currentKeyId(), identity.currentReceiverId(), identity.currentPublicKey());
    LanMetaRayReceiver receiver = new LanMetaRayReceiver(
        discovery,
        null,
        new JacksonJsonCodec(),
        target -> TestTlsMaterial.newTransport(new java.net.InetSocketAddress(target.host, target.controlPort)),
        null,
        identity
    );

    AtomicBoolean actionHandled = new AtomicBoolean(false);
    try {
      emitter.startAdvertising();
      emitter.startControlServer();
      emitter.pushEvent(new ContextFrame(
          new ContextFrame.Std("media", false, null),
          Map.of()
      ));
      AssetDescriptor asset = emitter.registerAsset(
          new AssetRegistration(AssetDescriptor.Type.IMAGE, new byte[] {1, 2, 3, 4}, "asset-mdns", 1_000L)
      );
      emitter.onAction(req -> actionHandled.set(true));

      EmitterConfig discovered = waitForEmitter(receiver, instanceId, Duration.ofSeconds(5));
      assertNotNull(discovered);
      assertEquals(instanceId, discovered.instanceId);
      assertEquals(controlPort, discovered.controlPort);

      assertNotNull(receiver.connect(discovered));
      ContextFrame snapshot = receiver.pullContext("media", PullOptions.latest());
      assertNotNull(snapshot);
      assertEquals("media", snapshot.std.contextId);

      AssetBytes bytes = receiver.fetchAsset(new AssetFetchRequest(asset.id, null, null, null));
      assertEquals(AssetBytes.Status.OK, bytes.status);
      assertEquals(4, bytes.bytes.length);

      ActionResponse response = receiver.sendAction(new ActionRequest("open", "idem-mdns-1", Map.of(), null));
      assertEquals(ActionResponse.Status.OK, response.status);
      assertTrue(actionHandled.get());
    } finally {
      emitter.shutdown();
      receiver.close();
    }
  }

  private static EmitterConfig waitForEmitter(
      LanMetaRayReceiver receiver,
      String instanceId,
      Duration timeout
  ) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      List<EmitterConfig> emitters = receiver.discover(Duration.ofMillis(250));
      assertFalse(emitters == null);
      for (EmitterConfig info : emitters) {
        if (instanceId.equals(info.instanceId)) return info;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Emitter not discovered via mDNS within timeout: " + instanceId);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
