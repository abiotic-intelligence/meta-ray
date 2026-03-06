package testkit;

import core.model.wire.DefaultSchemaRegistry;
import core.model.wire.WireMessage;
import core.spi.Codec;
import java.util.Arrays;
import java.util.Objects;

public final class ConformanceKit {
  private final DefaultSchemaRegistry schemaRegistry = new DefaultSchemaRegistry();

  public void assertRequiredStdFields(WireMessage<?> message) {
    schemaRegistry.validate(message);
  }

  public <T> void assertCodecRoundTrip(Codec codec, T value, Class<T> type) {
    Objects.requireNonNull(codec, "codec");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(type, "type");
    byte[] encoded = codec.encode(value);
    T decoded = decodeRequired(codec, encoded, type);
    if (decoded == null) {
      throw new AssertionError("Codec decode returned null");
    }
    if (!Arrays.equals(encoded, codec.encode(decoded))) {
      throw new AssertionError("Codec round-trip changed canonical payload bytes");
    }
  }

  public <T> GoldenPayload<T> captureGoldenPayload(Codec jsonCodec, Codec cborCodec, T value, Class<T> type) {
    Objects.requireNonNull(value, "value");
    GoldenPayload<T> golden = new GoldenPayload<>(type, jsonCodec.encode(value), cborCodec.encode(value));
    assertGoldenPayloadParity(jsonCodec, cborCodec, golden);
    return golden;
  }

  public <T> void assertGoldenPayloadParity(Codec jsonCodec, Codec cborCodec, GoldenPayload<T> golden) {
    Objects.requireNonNull(jsonCodec, "jsonCodec");
    Objects.requireNonNull(cborCodec, "cborCodec");
    Objects.requireNonNull(golden, "golden");

    Class<T> type = golden.type();
    byte[] jsonGolden = golden.jsonBytes();
    byte[] cborGolden = golden.cborBytes();

    T jsonDecoded = decodeRequired(jsonCodec, jsonGolden, type);
    T cborDecoded = decodeRequired(cborCodec, cborGolden, type);

    byte[] canonicalFromJson = jsonCodec.encode(jsonDecoded);
    byte[] canonicalFromCbor = jsonCodec.encode(cborDecoded);
    byte[] cborRoundTrip = cborCodec.encode(cborDecoded);

    if (!Arrays.equals(jsonGolden, canonicalFromJson)) {
      throw new AssertionError(
          "JSON codec payload diverged from golden bytes. expected="
              + toHex(jsonGolden)
              + ", actual="
              + toHex(canonicalFromJson));
    }
    if (!Arrays.equals(cborGolden, cborRoundTrip)) {
      throw new AssertionError(
          "CBOR codec payload diverged from golden bytes. expected="
              + toHex(cborGolden)
              + ", actual="
              + toHex(cborRoundTrip));
    }
    if (!Arrays.equals(canonicalFromJson, canonicalFromCbor)) {
      throw new AssertionError("JSON/CBOR decoded payloads are not semantically equivalent");
    }
  }

  private static <T> T decodeRequired(Codec codec, byte[] bytes, Class<T> type) {
    T decoded = codec.decode(bytes, type);
    if (decoded == null) {
      throw new AssertionError("Codec decode returned null");
    }
    return decoded;
  }

  public static final class GoldenPayload<T> {
    private final Class<T> type;
    private final byte[] jsonBytes;
    private final byte[] cborBytes;

    public GoldenPayload(Class<T> type, byte[] jsonBytes, byte[] cborBytes) {
      this.type = Objects.requireNonNull(type, "type");
      this.jsonBytes = Arrays.copyOf(Objects.requireNonNull(jsonBytes, "jsonBytes"), jsonBytes.length);
      this.cborBytes = Arrays.copyOf(Objects.requireNonNull(cborBytes, "cborBytes"), cborBytes.length);
    }

    public Class<T> type() {
      return type;
    }

    public byte[] jsonBytes() {
      return Arrays.copyOf(jsonBytes, jsonBytes.length);
    }

    public byte[] cborBytes() {
      return Arrays.copyOf(cborBytes, cborBytes.length);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      out.append(String.format("%02x", b));
    }
    return out.toString();
  }
}
