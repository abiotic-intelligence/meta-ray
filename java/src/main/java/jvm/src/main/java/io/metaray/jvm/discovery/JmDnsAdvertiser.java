package io.metaray.jvm.discovery;

import core.model.EmitterConfig;
import core.util.MetaRayException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public final class JmDnsAdvertiser implements AutoCloseable {
  public static final String CONTROL_SERVICE_TYPE = "_metaray._tcp.local.";
  public static final String MULTICAST_SERVICE_TYPE = "_metaray._udp.local.";

  private final JmDNS jmdns;
  private volatile ServiceInfo controlService;
  private volatile ServiceInfo multicastService;

  public JmDnsAdvertiser() {
    this(createDefaultJmDns());
  }

  public JmDnsAdvertiser(JmDNS jmdns) {
    this.jmdns = Objects.requireNonNull(jmdns, "jmdns must not be null");
  }

  public synchronized void start(EmitterConfig info) {
    Objects.requireNonNull(info, "info must not be null");
    stop();
    try {
      controlService = ServiceInfo.create(
          CONTROL_SERVICE_TYPE,
          info.instanceId,
          info.controlPort,
          0,
          0,
          true,
          txtFor(info)
      );
      jmdns.registerService(controlService);

      if (info.multicastGroup != null && info.multicastPort != null) {
        Map<String, String> txt = new LinkedHashMap<>(txtFor(info));
        txt.put("mgroup", info.multicastGroup);
        txt.put("mport", String.valueOf(info.multicastPort));
        multicastService = ServiceInfo.create(
            MULTICAST_SERVICE_TYPE,
            info.instanceId,
            info.multicastPort,
            0,
            0,
            true,
            txt
        );
        jmdns.registerService(multicastService);
      }
    } catch (IOException e) {
      throw new MetaRayException("Failed to register mDNS services", e);
    }
  }

  public synchronized void stop() {
    try {
      if (controlService != null) {
        jmdns.unregisterService(controlService);
      }
      if (multicastService != null) {
        jmdns.unregisterService(multicastService);
      }
    } catch (Exception ignored) {
      // Safe best-effort cleanup.
    } finally {
      controlService = null;
      multicastService = null;
    }
  }

  @Override
  public void close() {
    stop();
    try {
      jmdns.close();
    } catch (IOException e) {
      throw new MetaRayException("Failed to close JmDNS advertiser", e);
    }
  }

  private static Map<String, String> txtFor(EmitterConfig info) {
    Map<String, String> txt = new LinkedHashMap<>();
    txt.put("v", String.valueOf(info.protocolVersion));
    txt.put("app", info.appId);
    txt.put("eid", info.emitterId);
    if (info.emitterKeyId != null) txt.put("ek", info.emitterKeyId);
    if (info.deviceName != null) txt.put("dn", info.deviceName);
    txt.put("codecs", String.join(",", info.codecs));
    txt.put("sec", String.join(",", info.security));
    if (!info.capabilities.isEmpty()) txt.put("cap", String.join(",", info.capabilities));
    return txt;
  }

  private static JmDNS createDefaultJmDns() {
    try {
      return JmDNS.create(InetAddress.getLocalHost());
    } catch (IOException e) {
      throw new MetaRayException("Failed to initialize JmDNS advertiser", e);
    }
  }
}
