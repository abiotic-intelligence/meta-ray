package core.model.wire;

import core.util.Checks;
import java.util.Map;

public final class WireMessage<B> {
  public final Std<B> std;              // required
  public final Map<String, Object> ext; // optional (namespaced)

  public WireMessage(Std<B> std, Map<String, Object> ext) {
    this.std = Checks.notNull(std, "std");
    this.ext = (ext == null) ? Map.of() : Map.copyOf(ext);
  }

  public static final class Std<B> {
    public final int v;                 // required (1)
    public final MsgType msgType;       // required
    public final Header header;         // required
    public final B body;                // required (typed by msgType)

    // Optional localization metadata
    public final String locale;
    public final String region;

    public Std(
        int v,
        MsgType msgType,
        Header header,
        B body,
        String locale,
        String region
    ) {
      this.v = Checks.exact(v, 1, "v");
      this.msgType = Checks.notNull(msgType, "msgType");
      this.header = Checks.notNull(header, "header");
      this.body = Checks.notNull(body, "body");

      this.locale = blankToNull(locale);
      this.region = blankToNull(region);
    }
  }

  public static final class Header {
    public final String appId;        // required
    public final String id;           // required
    public final String deviceName;   // optional

    public final String sessionId;    // optional
    public final String keyId;        // optional
    public final String publicKey;    // optional
    public final String deviceModel;  // optional

    public Header(
        String appId,
        String id,
        String deviceName,
        String sessionId,
        String keyId,
        String publicKey,
        String deviceModel
    ) {
      this.appId = Checks.notBlank(appId, "header.appId");
      this.id = Checks.notBlank(id, "header.id");
      this.deviceName = blankToNull(deviceName);
      this.sessionId = blankToNull(sessionId);
      this.keyId = blankToNull(keyId);
      this.publicKey = blankToNull(publicKey);
      this.deviceModel = blankToNull(deviceModel);
    }
  }

  private static String blankToNull(String s) {
    if (s == null) return null;
    return s.isBlank() ? null : s;
  }
}
