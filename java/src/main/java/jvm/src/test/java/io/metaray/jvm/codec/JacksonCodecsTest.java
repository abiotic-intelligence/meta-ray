package io.metaray.jvm.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonCodecsTest {
  @Test
  void jsonCodecRoundTripMap() {
    JacksonJsonCodec codec = new JacksonJsonCodec();
    Map<String, Object> in = Map.of("a", 1, "b", "x");
    byte[] bytes = codec.encode(in);
    @SuppressWarnings("unchecked")
    Map<String, Object> out = codec.decode(bytes, Map.class);
    assertEquals("x", out.get("b"));
    assertEquals(1, ((Number) out.get("a")).intValue());
  }

  @Test
  void cborCodecRoundTripMap() {
    JacksonCborCodec codec = new JacksonCborCodec();
    Map<String, Object> in = Map.of("a", 1, "b", "x");
    byte[] bytes = codec.encode(in);
    @SuppressWarnings("unchecked")
    Map<String, Object> out = codec.decode(bytes, Map.class);
    assertEquals("x", out.get("b"));
    assertEquals(1, ((Number) out.get("a")).intValue());
  }
}
