package core.model.wire;

import core.util.Checks;

public final class DefaultSchemaRegistry implements SchemaRegistry {
  @Override
  public void validate(WireMessage<?> message) {
    WireMessage<?> m = Checks.notNull(message, "message");
    Checks.notNull(m.std, "std");
    Checks.notNull(m.std.msgType, "std.msgType");
    Checks.notNull(m.std.header, "std.header");
    Checks.notBlank(m.std.header.id, "std.header.id");
    Checks.notBlank(m.std.header.appId, "std.header.appId");
    Checks.exact(m.std.v, 1, "std.v");

    MessageRouter.requireBody(m.std.body, m.std.msgType);
    MessageRouter.validateExtNamespaces(m.ext);
  }
}
