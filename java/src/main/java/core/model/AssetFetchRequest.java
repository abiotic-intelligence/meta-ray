package core.model;

import core.util.Checks;

public final class AssetFetchRequest {
  public final String assetId;     // either assetId OR hash is required
  public final String hash;        // either hash OR assetId is required
  public final Long rangeStart;    // optional, must be paired with rangeLen
  public final Long rangeLen;      // optional, must be paired with rangeStart

  public AssetFetchRequest(String assetId, String hash, Long rangeStart, Long rangeLen) {
    this.assetId = blankToNull(assetId);
    this.hash = blankToNull(hash);

    boolean hasId = this.assetId != null;
    boolean hasHash = this.hash != null;

    if (hasId == hasHash) { // both true or both false
      throw new IllegalArgumentException("Exactly one of assetId or hash must be provided");
    }

    boolean hasStart = rangeStart != null;
    boolean hasLen = rangeLen != null;

    if (hasStart != hasLen) {
      throw new IllegalArgumentException("rangeStart and rangeLen must be provided together");
    }

    this.rangeStart = (rangeStart == null) ? null : Checks.nonNegative(rangeStart, "rangeStart");
    this.rangeLen = (rangeLen == null) ? null : positive(rangeLen, "rangeLen");
  }

  private static long positive(long v, String name) {
    if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
    return v;
  }

  private static String blankToNull(String s) {
    if (s == null) return null;
    return s.isBlank() ? null : s;
  }
}