package core.model;

import core.util.Checks;

public final class AssetBytes {
  public enum Status { OK, NOT_FOUND, ERROR }

  public final String assetId;     // required
  public final Status status;      // required
  public final Long rangeStart;    // optional (only meaningful for OK)
  public final Long rangeLen;      // optional (only meaningful for OK)
  public final byte[] bytes;       // non-null; non-empty if OK
  public final String errorCode;   // optional (required if status=ERROR)
  public final String errorMsg;    // optional (required if status=ERROR)

  public AssetBytes(
      String assetId,
      Status status,
      Long rangeStart,
      Long rangeLen,
      byte[] bytes,
      String errorCode,
      String errorMsg
  ) {
    this.assetId = Checks.notBlank(assetId, "assetId");
    this.status = Checks.notNull(status, "status");

    Checks.notNull(bytes, "bytes");
    this.bytes = bytes.clone(); // defensive copy

    boolean hasStart = rangeStart != null;
    boolean hasLen = rangeLen != null;
    if (hasStart != hasLen) {
      throw new IllegalArgumentException("rangeStart and rangeLen must be provided together");
    }
    this.rangeStart = (rangeStart == null) ? null : Checks.nonNegative(rangeStart, "rangeStart");
    this.rangeLen = (rangeLen == null) ? null : positive(rangeLen, "rangeLen");

    this.errorCode = blankToNull(errorCode);
    this.errorMsg = blankToNull(errorMsg);

    if (this.status == Status.OK) {
      if (this.bytes.length == 0) throw new IllegalArgumentException("bytes must not be empty when status=OK");
      if (this.errorCode != null || this.errorMsg != null) {
        throw new IllegalArgumentException("errorCode/errorMsg must be null when status=OK");
      }
    } else if (this.status == Status.NOT_FOUND) {
      if (this.bytes.length != 0) throw new IllegalArgumentException("bytes must be empty when status=NOT_FOUND");
      if (this.errorCode != null || this.errorMsg != null) {
        throw new IllegalArgumentException("errorCode/errorMsg must be null when status=NOT_FOUND");
      }
      if (this.rangeStart != null) {
        throw new IllegalArgumentException("rangeStart/rangeLen must be null when status=NOT_FOUND");
      }
    } else { // ERROR
      if (this.bytes.length != 0) throw new IllegalArgumentException("bytes must be empty when status=ERROR");
      if (this.errorCode == null && this.errorMsg == null) {
        throw new IllegalArgumentException("errorCode or errorMsg must be provided when status=ERROR");
      }
      if (this.rangeStart != null) {
        throw new IllegalArgumentException("rangeStart/rangeLen must be null when status=ERROR");
      }
    }
  }

  public static AssetBytes ok(String assetId, byte[] bytes) {
    return new AssetBytes(assetId, Status.OK, null, null, bytes, null, null);
  }

  public static AssetBytes okRange(String assetId, long rangeStart, long rangeLen, byte[] bytes) {
    return new AssetBytes(assetId, Status.OK, rangeStart, rangeLen, bytes, null, null);
  }

  public static AssetBytes notFound(String assetId) {
    return new AssetBytes(assetId, Status.NOT_FOUND, null, null, new byte[0], null, null);
  }

  public static AssetBytes error(String assetId, String errorCode, String errorMsg) {
    return new AssetBytes(assetId, Status.ERROR, null, null, new byte[0], errorCode, errorMsg);
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