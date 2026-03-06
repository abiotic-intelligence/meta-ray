package testkit;

import core.model.wire.DefaultSchemaRegistry;
import core.model.wire.WireMessage;
import core.spi.Codec;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class FuzzKit {
  private final Random random;
  private final DefaultSchemaRegistry schemaRegistry = new DefaultSchemaRegistry();

  public FuzzKit() {
    this(new SecureRandom().nextLong());
  }

  public FuzzKit(long seed) {
    this.random = new Random(seed);
  }

  public int fuzzDecode(Codec codec, Class<?> targetType, int iterations, int maxLength) {
    Objects.requireNonNull(codec, "codec");
    Objects.requireNonNull(targetType, "targetType");
    if (iterations < 1) throw new IllegalArgumentException("iterations must be >= 1");
    if (maxLength < 1) throw new IllegalArgumentException("maxLength must be >= 1");

    int exceptions = 0;
    for (int i = 0; i < iterations; i++) {
      int len = 1 + random.nextInt(maxLength);
      byte[] bytes = new byte[len];
      random.nextBytes(bytes);
      try {
        codec.decode(bytes, targetType);
      } catch (RuntimeException ex) {
        exceptions++;
      }
    }
    return exceptions;
  }

  public FuzzResult fuzzMalformedMaps(Codec codec, int iterations) {
    Objects.requireNonNull(codec, "codec");
    if (iterations < 1) throw new IllegalArgumentException("iterations must be >= 1");

    int rejected = 0;
    for (int i = 0; i < iterations; i++) {
      Map<String, Object> malformed = malformedWireMessageMap();
      try {
        WireMessage<?> decoded = codec.decode(codec.encode(malformed), WireMessage.class);
        schemaRegistry.validate(decoded);
      } catch (RuntimeException ex) {
        rejected++;
      }
    }
    return new FuzzResult(iterations, rejected);
  }

  private Map<String, Object> malformedWireMessageMap() {
    Map<String, Object> wire = validWireMessageMap();

    switch (random.nextInt(10)) {
      case 0 -> wire.remove("std");
      case 1 -> std(wire).put("v", 2);
      case 2 -> std(wire).put("msgType", "not_a_wire_type");
      case 3 -> header(wire).put("id", "");
      case 4 -> header(wire).remove("appId");
      case 5 -> std(wire).put("body", null);
      case 6 -> wire.put("ext", Map.of("invalid namespace", Map.of("x", 1)));
      case 7 -> std(wire).put("header", "invalid-header-object");
      case 8 -> wire.put("ext", Map.of("com..broken", Map.of("x", 1)));
      case 9 -> wire.put("std", List.of("invalid-std"));
      default -> throw new IllegalStateException("Unexpected mutation strategy");
    }
    return wire;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> std(Map<String, Object> wire) {
    return (Map<String, Object>) wire.get("std");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> header(Map<String, Object> wire) {
    return (Map<String, Object>) std(wire).get("header");
  }

  private static Map<String, Object> validWireMessageMap() {
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("appId", "app");
    header.put("id", "id-1");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("step", "hello");

    Map<String, Object> std = new LinkedHashMap<>();
    std.put("v", 1);
    std.put("msgType", "auth");
    std.put("header", header);
    std.put("body", body);

    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("std", std);
    wire.put("ext", Map.of("com.example", Map.of("ok", true)));
    return wire;
  }

  public static final class FuzzResult {
    public final int iterations;
    public final int rejected;

    public FuzzResult(int iterations, int rejected) {
      this.iterations = iterations;
      this.rejected = rejected;
    }

    public int accepted() {
      return iterations - rejected;
    }
  }
}
