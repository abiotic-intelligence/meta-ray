package io.metaray.jvm.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import core.model.EmitterConfig;
import java.util.Map;
import javax.jmdns.ServiceInfo;
import org.junit.jupiter.api.Test;

class JmDnsDiscoveryTest {
  @Test
  void mapsServiceInfoToEmitterConfig() {
    Map<String, ?> txt = Map.of(
        "v", "1",
        "app", "netflix",
        "eid", "3e9c8b2e-7e8c-4b4a-8b9a-3f0d6b6a1d11",
        "ek", "sha256:7e0cc91a",
        "dn", "Living Room TV"
    );

    ServiceInfo service = ServiceInfo.create(
        "_metaray._tcp.local.",
        "emitter-1",
        7777,
        0,
        0,
        true,
        txt
    );

    EmitterConfig info = JmDnsDiscovery.toEmitterConfig(service);
    assertEquals(1, info.protocolVersion);
    assertEquals("emitter-1", info.instanceId);
    assertEquals("netflix", info.appId);
    assertEquals("3e9c8b2e-7e8c-4b4a-8b9a-3f0d6b6a1d11", info.emitterId);
    assertEquals("sha256:7e0cc91a", info.emitterKeyId);
    assertEquals("Living Room TV", info.deviceName);
    assertEquals(7777, info.controlPort);
    assertNull(info.multicastGroup);
    assertNull(info.multicastPort);
    assertTrue(info.codecs.contains("cbor"));
    assertTrue(info.security.contains("tls"));
    assertTrue(info.capabilities.isEmpty());
  }

  @Test
  void fallsBackWhenShortTxtMissing() {
    ServiceInfo service = ServiceInfo.create(
        "_metaray._tcp.local.",
        "emitter-2",
        7000,
        0,
        0,
        true,
        Map.of("codecs", "json", "sec", "tls")
    );

    EmitterConfig info = JmDnsDiscovery.toEmitterConfig(service);
    assertEquals("metaray", info.appId);
    assertEquals("emitter-2", info.emitterId);
    assertNull(info.emitterKeyId);
    assertNull(info.deviceName);
    assertNull(info.multicastGroup);
    assertNull(info.multicastPort);
    assertTrue(info.codecs.contains("json"));
    assertTrue(info.security.contains("tls"));
  }

  @Test
  void appIdFilterMatchesCaseInsensitive() {
    ServiceInfo service = ServiceInfo.create(
        "_metaray._tcp.local.",
        "emitter-3",
        7001,
        0,
        0,
        true,
        Map.of("app", "NetFlix")
    );

    assertTrue(JmDnsDiscovery.matchesAppIdFilter(service, "netflix"));
    assertFalse(JmDnsDiscovery.matchesAppIdFilter(service, "spotify"));
    assertTrue(JmDnsDiscovery.matchesAppIdFilter(service, null));
  }
}
