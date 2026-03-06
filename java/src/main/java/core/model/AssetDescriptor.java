package core.model;

import core.util.Checks;

public final class AssetDescriptor {
  public enum Type { IMAGE, GIF }

  public final String id;        // required (session-scoped id)
  public final Type type;        // required
  public final String hash;      // required (recommended: sha256:<64hex>)
  public final Long sizeBytes;   // optional
  public final String fetch;     // required (control-channel token/path)
  public final Long ttlMs;       // optional

  public AssetDescriptor(String id, Type type, String hash, Long sizeBytes, String fetch, Long ttlMs) {
    this.id = Checks.notBlank(id, "id");
    this.type = Checks.notNull(type, "type");
    this.hash = Checks.notBlank(hash, "hash");
    this.fetch = Checks.notBlank(fetch, "fetch");
    this.sizeBytes = (sizeBytes == null) ? null : Checks.nonNegative(sizeBytes, "sizeBytes");
    this.ttlMs = (ttlMs == null) ? null : Checks.nonNegative(ttlMs, "ttlMs");

    // Catch common caching bugs early.
    validateSha256HashFormat(this.hash);
  }

  private static void validateSha256HashFormat(String hash) {
    // Strict enough to prevent accidental cache-key bugs, not strict about prefix case.
    // Expected: sha256:<64 hex>
    String h = hash.trim();
    int colon = h.indexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException("hash must be in the form 'sha256:<hex>'");
    }
    String algo = h.substring(0, colon);
    String hex = h.substring(colon + 1);

    if (!algo.equalsIgnoreCase("sha256")) {
      throw new IllegalArgumentException("hash algorithm must be sha256");
    }
    if (hex.length() != 64) {
      throw new IllegalArgumentException("sha256 hash must be 64 hex chars");
    }
    for (int i = 0; i < hex.length(); i++) {
      char c = hex.charAt(i);
      boolean ok = (c >= '0' && c <= '9')
          || (c >= 'a' && c <= 'f')
          || (c >= 'A' && c <= 'F');
      if (!ok) throw new IllegalArgumentException("sha256 hash contains non-hex character at index " + i);
    }
  }
}