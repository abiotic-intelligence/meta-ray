package io.metaray.android.discovery;

import core.model.EmitterConfig;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ManualDiscovery {
  private final List<EmitterConfig> configuredEmitters;

  public ManualDiscovery(List<EmitterConfig> configuredEmitters) {
    this.configuredEmitters = List.copyOf(Objects.requireNonNull(configuredEmitters, "configuredEmitters"));
  }

  public List<EmitterConfig> discover(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be >= 0");
    }
    return configuredEmitters;
  }

  public static EmitterConfig single(
      String instanceId,
      InetSocketAddress controlEndpoint,
      InetSocketAddress multicastEndpoint,
      Set<String> codecs,
      Set<String> security,
      Set<String> capabilities
  ) {
    Objects.requireNonNull(controlEndpoint, "controlEndpoint must not be null");
    return new EmitterConfig(
        1,
        instanceId,
        requireHost(controlEndpoint),
        controlEndpoint.getPort(),
        multicastEndpoint == null ? null : requireHost(multicastEndpoint),
        multicastEndpoint == null ? null : multicastEndpoint.getPort(),
        codecs,
        security,
        capabilities
    );
  }

  private static String requireHost(InetSocketAddress endpoint) {
    if (endpoint.getAddress() != null) {
      return endpoint.getAddress().getHostAddress();
    }
    String host = endpoint.getHostString();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("Endpoint host must not be blank");
    }
    return host;
  }
}
