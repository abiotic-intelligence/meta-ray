package core.model;

import core.util.Checks;

public final class SessionKeys {
  public final byte[] groupKey;
  public final byte[] controlKey;
  public final String sessionId;
  public final long expiryEpochMs;

  public SessionKeys(byte[] groupKey, byte[] controlKey, String sessionId, long expiryEpochMs) {
    Checks.notNull(groupKey, "groupKey");
    Checks.notNull(controlKey, "controlKey");
    if (groupKey.length == 0) throw new IllegalArgumentException("groupKey must not be empty");
    if (controlKey.length == 0) throw new IllegalArgumentException("controlKey must not be empty");
    this.groupKey = groupKey.clone();
    this.controlKey = controlKey.clone();
    this.sessionId = Checks.notBlank(sessionId, "sessionId");
    this.expiryEpochMs = Checks.nonNegative(expiryEpochMs, "expiryEpochMs");
  }
}
