package io.metaray.jvm.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import core.model.EmitterConfig;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManualDiscoveryTest {
  @Test
  void returnsConfiguredEmitter() {
    EmitterConfig emitter = ManualDiscovery.single(
        "emitter-1",
        new InetSocketAddress("127.0.0.1", 7777),
        new InetSocketAddress("239.1.2.3", 5001),
        Set.of("json", "cbor"),
        Set.of("tls"),
        Set.of("assets")
    );

    ManualDiscovery discovery = new ManualDiscovery(List.of(emitter));
    List<EmitterConfig> result = discovery.discover(Duration.ofMillis(10));

    assertEquals(1, result.size());
    assertEquals("emitter-1", result.get(0).instanceId);
    assertEquals(7777, result.get(0).controlPort);
  }
}
