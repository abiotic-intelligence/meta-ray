package core.model.wire;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import core.model.ActionResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultSchemaRegistryTest {
  @Test
  void validatesWellFormedMessage() {
    WireMessage.Header header = new WireMessage.Header("app", "id-1", "device", "s1", null, null, null);
    WireMessage<ActionResponse> message = new WireMessage<>(
        new WireMessage.Std<>(1, MsgType.ACTION_RESPONSE, header, ActionResponse.ok(Map.of("a", true)), null, null),
        Map.of("com.example", Map.of("k", "v"))
    );
    DefaultSchemaRegistry registry = new DefaultSchemaRegistry();
    assertDoesNotThrow(() -> registry.validate(message));
  }

  @Test
  void rejectsInvalidExtNamespace() {
    WireMessage.Header header = new WireMessage.Header("app", "id-2", "device", "s1", null, null, null);
    WireMessage<ActionResponse> message = new WireMessage<>(
        new WireMessage.Std<>(1, MsgType.ACTION_RESPONSE, header, ActionResponse.ok(Map.of()), null, null),
        Map.of("invalid", Map.of())
    );
    DefaultSchemaRegistry registry = new DefaultSchemaRegistry();
    assertThrows(IllegalArgumentException.class, () -> registry.validate(message));
  }

  @Test
  void allowsAuthWithoutDeviceName() {
    WireMessage.Header header = new WireMessage.Header("app", "receiver-id", null, null, "sha256:key", "pk", null);
    WireMessage<AuthMessage> message = new WireMessage<>(
        new WireMessage.Std<>(1, MsgType.AUTH, header, AuthMessage.hello(), null, null),
        Map.of()
    );
    DefaultSchemaRegistry registry = new DefaultSchemaRegistry();
    assertDoesNotThrow(() -> registry.validate(message));
  }
}
