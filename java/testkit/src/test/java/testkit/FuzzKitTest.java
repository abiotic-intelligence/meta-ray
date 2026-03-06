package testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.metaray.jvm.codec.JacksonCborCodec;
import io.metaray.jvm.codec.JacksonJsonCodec;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FuzzKitTest {
  @Test
  void fuzzDecodeProducesSomeFailuresOnRandomInput() {
    FuzzKit fuzz = new FuzzKit();
    int exceptions = fuzz.fuzzDecode(new JacksonJsonCodec(), Map.class, 50, 64);
    assertTrue(exceptions > 0);
  }

  @Test
  void malformedMapFuzzRejectsAllGeneratedCases() {
    FuzzKit.FuzzResult json = new FuzzKit(7L).fuzzMalformedMaps(new JacksonJsonCodec(), 200);
    FuzzKit.FuzzResult cbor = new FuzzKit(7L).fuzzMalformedMaps(new JacksonCborCodec(), 200);

    assertEquals(200, json.rejected);
    assertEquals(0, json.accepted());
    assertEquals(200, cbor.rejected);
    assertEquals(0, cbor.accepted());
  }
}
