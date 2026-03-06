package core.model;

import core.util.Checks;

public final class AssetRegistration {
  public final AssetDescriptor.Type type; // required
  public final byte[] bytes;              // required, non-empty
  public final String suggestedId;        // optional (emitter may ignore)
  public final Long ttlMs;                // optional

  public AssetRegistration(AssetDescriptor.Type type, byte[] bytes, String suggestedId, Long ttlMs) {
    this.type = Checks.notNull(type, "type");
    Checks.notNull(bytes, "bytes");
    if (bytes.length == 0) throw new IllegalArgumentException("bytes must not be empty");
    this.bytes = bytes.clone(); // defensive copy
    this.suggestedId = (suggestedId == null || suggestedId.isBlank()) ? null : suggestedId;
    this.ttlMs = (ttlMs == null) ? null : Checks.nonNegative(ttlMs, "ttlMs");
  }
}