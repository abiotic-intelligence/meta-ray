package io.metaray.jvm.discovery;

import core.model.EmitterConfig;
import core.util.MetaRayException;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public final class JmDnsDiscovery implements AutoCloseable {
  public static final String DEFAULT_CONTROL_SERVICE_TYPE = "_metaray._tcp.local.";

  private final JmDNS jmdns;
  private final String serviceType;
  private final String appIdFilter;

  public JmDnsDiscovery() {
    this(DEFAULT_CONTROL_SERVICE_TYPE, null);
  }

  public JmDnsDiscovery(String serviceType) {
    this(serviceType, null);
  }

  public JmDnsDiscovery(String serviceType, String appIdFilter) {
    this(createDefaultJmDns(), serviceType, appIdFilter);
  }

  public JmDnsDiscovery(JmDNS jmdns, String serviceType) {
    this(jmdns, serviceType, null);
  }

  public JmDnsDiscovery(JmDNS jmdns, String serviceType, String appIdFilter) {
    this.jmdns = Objects.requireNonNull(jmdns, "jmdns must not be null");
    this.serviceType = normalizeServiceType(serviceType);
    this.appIdFilter = normalizeAppIdFilter(appIdFilter);
  }

  public List<EmitterConfig> discover(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be >= 0");
    }
    long timeoutMs = timeout.toMillis();
    ServiceInfo[] services = jmdns.list(serviceType, timeoutMs);
    return Arrays.stream(services)
        .map(service -> toEmitterConfigSafe(service, appIdFilter))
        .flatMap(java.util.Optional::stream)
        .toList();
  }

  @Override
  public void close() {
    try {
      jmdns.close();
    } catch (IOException e) {
      throw new MetaRayException("Failed to close JmDNS", e);
    }
  }

  static EmitterConfig toEmitterConfig(ServiceInfo service) {
    Objects.requireNonNull(service, "service must not be null");
    String host = firstHost(service);
    String instanceId = notBlankOrElse(service.getName(), service.getQualifiedName());
    int protocolVersion = parseIntOrDefault(service.getPropertyString("v"), 1);
    String appId = notBlankOrElse(service.getPropertyString("app"), "metaray");
    String emitterId = notBlankOrElse(service.getPropertyString("eid"), instanceId);
    String emitterKeyId = blankToNull(service.getPropertyString("ek"));
    String deviceName = blankToNull(service.getPropertyString("dn"));

    Set<String> codecs = parseCsvSet(service.getPropertyString("codecs"));
    if (codecs.isEmpty()) codecs = Set.of("cbor", "json");

    Set<String> security = parseCsvSet(service.getPropertyString("sec"));
    if (security.isEmpty()) security = Set.of("tls");

    Set<String> capabilities = parseCsvSet(service.getPropertyString("cap"));

    String mgroup = firstNonBlank(
        service.getPropertyString("mgroup"),
        service.getPropertyString("multicast_group")
    );
    Integer mport = parsePort(firstNonBlank(
        service.getPropertyString("mport"),
        service.getPropertyString("multicast_port")
    ));

    return new EmitterConfig(
        protocolVersion,
        instanceId,
        appId,
        emitterId,
        emitterKeyId,
        deviceName,
        host,
        service.getPort(),
        mgroup,
        mport,
        codecs,
        security,
        capabilities
    );
  }

  private static java.util.Optional<EmitterConfig> toEmitterConfigSafe(ServiceInfo service, String appIdFilter) {
    try {
      if (!matchesAppIdFilter(service, appIdFilter)) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(toEmitterConfig(service));
    } catch (RuntimeException ignored) {
      return java.util.Optional.empty();
    }
  }

  static boolean matchesAppIdFilter(ServiceInfo service, String appIdFilter) {
    if (appIdFilter == null) return true;
    String appId = blankToNull(service.getPropertyString("app"));
    return appId != null && appIdFilter.equalsIgnoreCase(appId);
  }

  private static JmDNS createDefaultJmDns() {
    try {
      return JmDNS.create(InetAddress.getLocalHost());
    } catch (IOException e) {
      throw new MetaRayException("Failed to initialize JmDNS", e);
    }
  }

  private static String normalizeServiceType(String value) {
    String type = notBlankOrElse(value, DEFAULT_CONTROL_SERVICE_TYPE);
    return type.endsWith(".") ? type : type + ".";
  }

  private static String normalizeAppIdFilter(String value) {
    return blankToNull(value);
  }

  private static String firstHost(ServiceInfo service) {
    InetAddress[] ipv4 = service.getInet4Addresses();
    if (ipv4 != null && ipv4.length > 0) {
      return ipv4[0].getHostAddress();
    }
    InetAddress[] all = service.getInetAddresses();
    if (all != null && all.length > 0) {
      return all[0].getHostAddress();
    }
    String server = blankToNull(service.getServer());
    if (server != null) {
      return server.endsWith(".") ? server.substring(0, server.length() - 1) : server;
    }
    // Some synthetic ServiceInfo instances (tests/manual stubs) have no resolved address.
    // Real discoveries usually resolve to an address; fallback keeps mapping deterministic.
    return "127.0.0.1";
  }

  private static Set<String> parseCsvSet(String value) {
    String raw = blankToNull(value);
    if (raw == null) return Set.of();
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.toLowerCase(Locale.ROOT))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Integer parsePort(String value) {
    String raw = blankToNull(value);
    if (raw == null) return null;
    int parsed = Integer.parseInt(raw);
    if (parsed < 1 || parsed > 65535) {
      throw new IllegalArgumentException("multicast port must be in [1..65535]");
    }
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

  private static String notBlankOrElse(String value, String fallback) {
    String v = blankToNull(value);
    return v != null ? v : Objects.requireNonNull(fallback, "fallback must not be null");
  }

  private static String blankToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
