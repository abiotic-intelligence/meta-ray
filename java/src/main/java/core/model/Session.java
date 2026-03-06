package core.model;

import core.util.Checks;
import java.util.Locale;

public final class Session {
  public final String sessionId;
  public final String codec;
  public final String security;

  public Session(String sessionId) {
    this(sessionId, null, null);
  }

  public Session(String sessionId, String codec, String security) {
    this.sessionId = Checks.notBlank(sessionId, "sessionId");
    this.codec = normalizedOrNull(codec);
    this.security = normalizedOrNull(security);
  }

  private static String normalizedOrNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
  }
}
