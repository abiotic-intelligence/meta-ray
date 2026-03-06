package core.model;

import core.util.Checks;
import java.util.Set;

public final class EmitterConfig {
  public final int protocolVersion;     // required (v0.1 => 1)
  public final String instanceId;       // required (stable within LAN discovery scope)
  public final String appId;            // required for app-scoped mDNS discovery
  public final String emitterId;        // required stable emitter identity
  public final String emitterKeyId;     // optional key fingerprint (e.g. sha256:...)
  public final String deviceName;       // optional friendly device name
  public final String host;             // required (IP or hostname)
  public final int controlPort;         // required
  public final String multicastGroup;   // optional (may be null if push not supported)
  public final Integer multicastPort;   // optional (paired with multicastGroup)
  public final Set<String> codecs;      // required, non-empty (e.g. "cbor", "json")
  public final Set<String> security;    // required, non-empty (e.g. "dtls", "tls")
  public final Set<String> capabilities;// optional (may be empty)

  public EmitterConfig(
      int protocolVersion,
      String instanceId,
      String host,
      int controlPort,
      String multicastGroup,
      Integer multicastPort,
      Set<String> codecs,
      Set<String> security,
      Set<String> capabilities
  ) {
    this(
        protocolVersion,
        instanceId,
        "metaray",
        instanceId,
        null,
        instanceId,
        host,
        controlPort,
        multicastGroup,
        multicastPort,
        codecs,
        security,
        capabilities
    );
  }

  public EmitterConfig(
      int protocolVersion,
      String instanceId,
      String appId,
      String emitterId,
      String emitterKeyId,
      String deviceName,
      String host,
      int controlPort,
      String multicastGroup,
      Integer multicastPort,
      Set<String> codecs,
      Set<String> security,
      Set<String> capabilities
  ) {
    this.protocolVersion = Checks.exact(protocolVersion, 1, "protocolVersion");
    this.instanceId = Checks.notBlank(instanceId, "instanceId");
    this.appId = Checks.notBlank(appId, "appId");
    this.emitterId = Checks.notBlank(emitterId, "emitterId");
    this.emitterKeyId = (emitterKeyId == null || emitterKeyId.isBlank()) ? null : emitterKeyId;
    this.deviceName = (deviceName == null || deviceName.isBlank()) ? null : deviceName;
    this.host = Checks.notBlank(host, "host");
    this.controlPort = port(controlPort, "controlPort");

    this.multicastGroup = (multicastGroup == null || multicastGroup.isBlank()) ? null : multicastGroup;
    this.multicastPort = multicastPort;

    boolean hasGroup = this.multicastGroup != null;
    boolean hasMPort = multicastPort != null;
    if (hasGroup != hasMPort) {
      throw new IllegalArgumentException("multicastGroup and multicastPort must be provided together (or both omitted)");
    }
    if (multicastPort != null) port(multicastPort, "multicastPort");

    this.codecs = Set.copyOf(Checks.notNull(codecs, "codecs"));
    if (this.codecs.isEmpty()) throw new IllegalArgumentException("codecs must not be empty");

    this.security = Set.copyOf(Checks.notNull(security, "security"));
    if (this.security.isEmpty()) throw new IllegalArgumentException("security must not be empty");

    this.capabilities = (capabilities == null) ? Set.of() : Set.copyOf(capabilities);
  }

  private static int port(int v, String name) {
    if (v < 1 || v > 65535) throw new IllegalArgumentException(name + " must be in [1..65535]");
    return v;
  }
}
