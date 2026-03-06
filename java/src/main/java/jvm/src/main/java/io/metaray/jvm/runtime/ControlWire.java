package io.metaray.jvm.runtime;

import core.model.wire.DefaultSchemaRegistry;
import core.model.wire.MessageRouter;
import core.model.wire.MsgType;
import core.model.wire.SchemaRegistry;
import core.model.wire.WireMessage;
import core.spi.Codec;
import core.util.Checks;
import java.util.Map;

final class ControlWire {
  static final String BOOTSTRAP_SESSION_ID = "bootstrap";
  private static final SchemaRegistry SCHEMA = new DefaultSchemaRegistry();

  private ControlWire() {}

  static <T> byte[] encode(
      Codec codec,
      MsgType msgType,
      String sessionId,
      String keyId,
      long seq,
      T body
  ) {
    Checks.notNull(codec, "codec");
    WireMessage.Header header = new WireMessage.Header(
        "metaray",
        Long.toString(seq),
        "metaray-device",
        sessionId,
        keyId,
        null,
        null
    );
    WireMessage<T> wire = new WireMessage<>(
        new WireMessage.Std<>(1, msgType, header, body, null, null),
        Map.of()
    );
    SCHEMA.validate(wire);
    return codec.encode(wire);
  }

  static WireMessage<?> decode(Codec codec, byte[] bytes) {
    Checks.notNull(codec, "codec");
    RawEnvelope raw = codec.decode(bytes, RawEnvelope.class);
    Checks.notNull(raw, "raw");
    Checks.notNull(raw.std, "raw.std");
    Checks.notNull(raw.std.header, "raw.std.header");
    MsgType type = Checks.notNull(raw.std.msgType, "raw.std.msgType");

    WireMessage.Header header = toHeader(raw.std.header);
    Object body = decodeTypedBody(codec, type, raw.std.body);

    WireMessage.Std<Object> std = new WireMessage.Std<>(
        raw.std.v,
        type,
        header,
        body,
        raw.std.locale,
        raw.std.region
    );
    WireMessage<Object> wire = new WireMessage<>(std, raw.ext);
    SCHEMA.validate(wire);
    return wire;
  }

  static <T> T bodyAs(WireMessage<?> wire, Class<T> type) {
    Checks.notNull(wire, "wire");
    Checks.notNull(type, "type");
    Object body = wire.std.body;
    if (type.isInstance(body)) {
      return type.cast(body);
    }
    if (body == null) {
      throw new IllegalArgumentException("Unexpected null body for " + type.getSimpleName());
    }
    throw new IllegalArgumentException(
        "Unexpected body type. expected=" + type.getSimpleName() + " actual=" + body.getClass().getName());
  }

  private static WireMessage.Header toHeader(RawHeader rawHeader) {
    return new WireMessage.Header(
        rawHeader.appId,
        rawHeader.id,
        rawHeader.deviceName,
        rawHeader.sessionId,
        rawHeader.keyId,
        rawHeader.publicKey,
        rawHeader.deviceModel
    );
  }

  private static Object decodeTypedBody(Codec codec, MsgType type, Object rawBody) {
    if (rawBody == null) return null;
    Class<?> bodyType = MessageRouter.bodyClassFor(type);
    if (bodyType.isInstance(rawBody)) return rawBody;
    return codec.decode(codec.encode(rawBody), bodyType);
  }

  static final class RawEnvelope {
    public RawStd std;
    public Map<String, Object> ext;

    public RawEnvelope() {}
  }

  static final class RawStd {
    public int v;
    public MsgType msgType;
    public RawHeader header;
    public Object body;
    public String locale;
    public String region;

    public RawStd() {}
  }

  static final class RawHeader {
    public String appId;
    public String id;
    public String sessionId;
    public String deviceName;
    public String keyId;
    public String publicKey;
    public String deviceModel;

    public RawHeader() {}
  }
}
