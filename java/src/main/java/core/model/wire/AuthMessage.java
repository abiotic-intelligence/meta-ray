package core.model.wire;

import core.util.Checks;

public final class AuthMessage {
  public final String step;
  public final String nonce;
  public final String proof;
  public final String sessionId;
  public final String reason;

  public AuthMessage(String step, String nonce, String proof, String sessionId, String reason) {
    this.step = Checks.notBlank(step, "step");
    this.nonce = blankToNull(nonce);
    this.proof = blankToNull(proof);
    this.sessionId = blankToNull(sessionId);
    this.reason = blankToNull(reason);
  }

  public static AuthMessage hello() {
    return new AuthMessage("hello", null, null, null, null);
  }

  public static AuthMessage pair() {
    return new AuthMessage("pair", null, null, null, null);
  }

  public static AuthMessage pairRequired() {
    return new AuthMessage("pairRequired", null, null, null, null);
  }

  public static AuthMessage challenge(String nonce) {
    return new AuthMessage("challenge", Checks.notBlank(nonce, "nonce"), null, null, null);
  }

  public static AuthMessage proof(String nonce, String proof) {
    return new AuthMessage("proof", Checks.notBlank(nonce, "nonce"), Checks.notBlank(proof, "proof"), null, null);
  }

  public static AuthMessage ok(String sessionId) {
    return new AuthMessage("ok", null, null, Checks.notBlank(sessionId, "sessionId"), null);
  }

  public static AuthMessage deny(String reason) {
    return new AuthMessage("deny", null, null, null, Checks.notBlank(reason, "reason"));
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    return value.isBlank() ? null : value;
  }
}
