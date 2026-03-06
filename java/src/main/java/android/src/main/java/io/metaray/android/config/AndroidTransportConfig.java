package io.metaray.android.config;

import java.time.Duration;
import java.util.Objects;

public final class AndroidTransportConfig {
  public final Duration connectTimeout;
  public final Duration requestTimeout;
  public final int multicastLocalPort;

  public AndroidTransportConfig(Duration connectTimeout, Duration requestTimeout, int multicastLocalPort) {
    this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
    this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    this.multicastLocalPort = requirePort(multicastLocalPort, "multicastLocalPort");
  }

  public static AndroidTransportConfig defaults() {
    return new AndroidTransportConfig(Duration.ofSeconds(3), Duration.ofSeconds(5), 0);
  }

  private static Duration requirePositive(Duration d, String name) {
    Objects.requireNonNull(d, name + " must not be null");
    if (d.isZero() || d.isNegative()) {
      throw new IllegalArgumentException(name + " must be > 0");
    }
    return d;
  }

  private static int requirePort(int value, String name) {
    if (value < 0 || value > 65535) {
      throw new IllegalArgumentException(name + " must be in [0..65535]");
    }
    return value;
  }
}
