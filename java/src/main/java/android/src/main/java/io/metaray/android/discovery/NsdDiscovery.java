package io.metaray.android.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import core.model.EmitterConfig;
import core.util.MetaRayException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class NsdDiscovery {
  public static final String DEFAULT_SERVICE_TYPE = "_metaray._tcp.";

  private final NsdManager nsdManager;
  private final String serviceType;
  private final String appIdFilter;

  public NsdDiscovery(Context context) {
    this(context, DEFAULT_SERVICE_TYPE, null);
  }

  public NsdDiscovery(Context context, String serviceType) {
    this(context, serviceType, null);
  }

  public NsdDiscovery(Context context, String serviceType, String appIdFilter) {
    Context app = context.getApplicationContext();
    this.nsdManager = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
    if (this.nsdManager == null) {
      throw new MetaRayException("NsdManager unavailable");
    }
    this.serviceType = normalizeServiceType(serviceType);
    this.appIdFilter = normalizeAppIdFilter(appIdFilter);
  }

  public List<EmitterConfig> discover(Duration timeout) {
    if (timeout == null) throw new IllegalArgumentException("timeout must not be null");
    if (timeout.isNegative()) throw new IllegalArgumentException("timeout must be >= 0");

    List<EmitterConfig> results = new ArrayList<>();
    CountDownLatch wait = new CountDownLatch(1);
    Map<String, Boolean> resolvedNames = new ConcurrentHashMap<>();

    NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
      @Override
      public void onDiscoveryStarted(String regType) {
      }

      @Override
      public void onServiceFound(NsdServiceInfo serviceInfo) {
        if (!serviceType.equals(serviceInfo.getServiceType())) return;
        if (resolvedNames.putIfAbsent(serviceInfo.getServiceName(), Boolean.TRUE) != null) return;
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
          @Override
          public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
          }

          @Override
          public void onServiceResolved(NsdServiceInfo resolved) {
            try {
              if (matchesAppIdFilter(resolved, appIdFilter)) {
                results.add(toEmitterConfig(resolved));
              }
            } catch (RuntimeException ignored) {
            }
          }
        });
      }

      @Override
      public void onServiceLost(NsdServiceInfo serviceInfo) {
      }

      @Override
      public void onDiscoveryStopped(String serviceType) {
        wait.countDown();
      }

      @Override
      public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        wait.countDown();
      }

      @Override
      public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        wait.countDown();
      }
    };

    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
    try {
      wait.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MetaRayException("Interrupted while waiting for NSD discovery", e);
    } finally {
      try {
        nsdManager.stopServiceDiscovery(listener);
      } catch (RuntimeException ignored) {
      }
    }
    return List.copyOf(results);
  }

  static EmitterConfig toEmitterConfig(NsdServiceInfo service) {
    InetAddress hostAddr = service.getHost();
    String host = hostAddr != null ? hostAddr.getHostAddress() : "127.0.0.1";
    int protocolVersion = parseIntOrDefault(txt(service, "v"), 1);
    String instanceId = blankToNull(service.getServiceName());
    if (instanceId == null) {
      throw new IllegalArgumentException("service name must not be blank");
    }
    String appId = firstNonBlank(txt(service, "app"), "metaray");
    String emitterId = firstNonBlank(txt(service, "eid"), instanceId);
    String emitterKeyId = blankToNull(txt(service, "ek"));
    String deviceName = blankToNull(txt(service, "dn"));

    Set<String> codecs = parseCsvSet(txt(service, "codecs"));
    if (codecs.isEmpty()) codecs = Set.of("cbor", "json");

    Set<String> security = parseCsvSet(txt(service, "sec"));
    if (security.isEmpty()) security = Set.of("tls");

    Set<String> capabilities = parseCsvSet(txt(service, "cap"));

    String multicastGroup = firstNonBlank(txt(service, "mgroup"), txt(service, "multicast_group"));
    Integer multicastPort = parsePort(firstNonBlank(txt(service, "mport"), txt(service, "multicast_port")));

    return new EmitterConfig(
        protocolVersion,
        instanceId,
        appId,
        emitterId,
        emitterKeyId,
        deviceName,
        host,
        service.getPort(),
        multicastGroup,
        multicastPort,
        codecs,
        security,
        capabilities
    );
  }

  private static String txt(NsdServiceInfo service, String key) {
    byte[] value = service.getAttributes().get(key);
    return value == null ? null : new String(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String normalizeServiceType(String value) {
    if (value == null || value.isBlank()) return DEFAULT_SERVICE_TYPE;
    return value.endsWith(".") ? value : value + ".";
  }

  private static String normalizeAppIdFilter(String value) {
    return blankToNull(value);
  }

  private static boolean matchesAppIdFilter(NsdServiceInfo service, String appIdFilter) {
    if (appIdFilter == null) return true;
    String appId = blankToNull(txt(service, "app"));
    return appId != null && appIdFilter.equalsIgnoreCase(appId);
  }

  private static Set<String> parseCsvSet(String value) {
    String raw = blankToNull(value);
    if (raw == null) return Set.of();
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private static Integer parsePort(String value) {
    String raw = blankToNull(value);
    if (raw == null) return null;
    int parsed = Integer.parseInt(raw);
    if (parsed < 1 || parsed > 65535) throw new IllegalArgumentException("port out of range");
    return parsed;
  }

  private static int parseIntOrDefault(String value, int fallback) {
    String raw = blankToNull(value);
    return raw == null ? fallback : Integer.parseInt(raw);
  }

  private static String firstNonBlank(String a, String b) {
    String aa = blankToNull(a);
    if (aa != null) return aa;
    return blankToNull(b);
  }

  private static String blankToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
