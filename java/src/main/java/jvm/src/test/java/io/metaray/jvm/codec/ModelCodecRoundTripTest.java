package io.metaray.jvm.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import core.model.ActionRequest;
import core.model.ContextFrame;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelCodecRoundTripTest {
  @Test
  void roundTripActionRequest() {
    JacksonJsonCodec codec = new JacksonJsonCodec();
    ActionRequest in = new ActionRequest("a1", "k1", Map.of("x", 1), "r1");
    ActionRequest out = codec.decode(codec.encode(in), ActionRequest.class);
    assertEquals("a1", out.actionId);
    assertEquals("k1", out.idempotencyKey);
    assertEquals("r1", out.receiverId);
  }

  @Test
  void roundTripContextFrame() {
    JacksonJsonCodec codec = new JacksonJsonCodec();
    ContextFrame in = new ContextFrame(
        new ContextFrame.Std("media", true, "evt-1"),
        Map.of()
    );
    ContextFrame out = codec.decode(codec.encode(in), ContextFrame.class);
    assertEquals("media", out.std.contextId);
    assertEquals(true, out.std.isEvent);
    assertEquals("evt-1", out.std.eventId);
  }
}
