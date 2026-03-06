package io.metaray.jvm.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import core.model.wire.WireMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import testkit.ConformanceKit;
import testkit.FuzzKit;

class TestkitConformanceIntegrationTest {
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void testkitGoldenParityValidatesWirePayloadAcrossCodecs() {
    ConformanceKit kit = new ConformanceKit();
    JacksonJsonCodec json = new JacksonJsonCodec();
    JacksonCborCodec cbor = new JacksonCborCodec();

    LinkedHashMap<String, Object> header = new LinkedHashMap<>();
    header.put("appId", "app");
    header.put("id", "id-1");

    LinkedHashMap<String, Object> body = new LinkedHashMap<>();
    body.put("step", "hello");

    LinkedHashMap<String, Object> std = new LinkedHashMap<>();
    std.put("v", 1);
    std.put("msgType", "auth");
    std.put("header", header);
    std.put("body", body);

    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("std", std);
    payload.put("ext", Map.of("com.example", Map.of("x", 1)));

    Class<LinkedHashMap<String, Object>> mapType = (Class<LinkedHashMap<String, Object>>) (Class<?>) LinkedHashMap.class;
    ConformanceKit.GoldenPayload<LinkedHashMap<String, Object>> golden =
        kit.captureGoldenPayload(json, cbor, payload, mapType);

    kit.assertGoldenPayloadParity(json, cbor, golden);
    kit.assertRequiredStdFields(json.decode(golden.jsonBytes(), WireMessage.class));
    kit.assertRequiredStdFields(cbor.decode(golden.cborBytes(), WireMessage.class));
  }

  @Test
  void malformedMapFuzzRejectsInvalidWireShapes() {
    FuzzKit.FuzzResult result = new FuzzKit(17L).fuzzMalformedMaps(new JacksonJsonCodec(), 128);
    assertEquals(128, result.rejected);
    assertEquals(0, result.accepted());
  }
}
