package testkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import core.model.wire.ErrorMessage;
import core.model.wire.MsgType;
import core.model.wire.WireMessage;
import io.metaray.jvm.codec.JacksonCborCodec;
import io.metaray.jvm.codec.JacksonJsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConformanceKitTest {
  @Test
  void validatesWireMessageAndCodecRoundTrip() {
    ConformanceKit kit = new ConformanceKit();
    WireMessage.Header header = new WireMessage.Header("app", "id-1", "device", "s1", null, null, null);
    WireMessage<ErrorMessage> message = new WireMessage<>(
        new WireMessage.Std<>(1, MsgType.ERROR, header, new ErrorMessage("E", "msg", null, null, null), null, null),
        Map.of("com.example", Map.of("x", 1))
    );
    assertDoesNotThrow(() -> kit.assertRequiredStdFields(message));
    assertDoesNotThrow(() -> kit.assertCodecRoundTrip(new JacksonJsonCodec(), new LinkedHashMap<>(), LinkedHashMap.class));
  }

  @Test
  void validatesFixedGoldenParityAcrossJsonAndCbor() {
    ConformanceKit kit = new ConformanceKit();
    ConformanceKit.GoldenPayload<LinkedHashMap<String, Object>> golden = new ConformanceKit.GoldenPayload<>(
        linkedHashMapType(),
        "{\"a\":\"1\",\"b\":{\"x\":\"true\"}}".getBytes(StandardCharsets.UTF_8),
        new byte[] {
            (byte) 0xBF, 0x61, 0x61, 0x61, 0x31, 0x61, 0x62, (byte) 0xBF, 0x61, 0x78, 0x64, 0x74, 0x72, 0x75, 0x65, (byte) 0xFF, (byte) 0xFF}
    );

    assertDoesNotThrow(() -> kit.assertGoldenPayloadParity(new JacksonJsonCodec(), new JacksonCborCodec(), golden));
  }

  @Test
  void capturesGoldenPayloadFromValueAndRevalidates() {
    ConformanceKit kit = new ConformanceKit();
    LinkedHashMap<String, Object> value = new LinkedHashMap<>();
    value.put("alpha", 1);
    value.put("beta", Map.of("enabled", true));

    ConformanceKit.GoldenPayload<LinkedHashMap<String, Object>> golden = kit.captureGoldenPayload(
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        value,
        linkedHashMapType()
    );

    assertDoesNotThrow(() -> kit.assertGoldenPayloadParity(new JacksonJsonCodec(), new JacksonCborCodec(), golden));
  }

  @SuppressWarnings("unchecked")
  private static Class<LinkedHashMap<String, Object>> linkedHashMapType() {
    return (Class<LinkedHashMap<String, Object>>) (Class<?>) LinkedHashMap.class;
  }
}
