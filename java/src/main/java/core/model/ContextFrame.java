package core.model;

import core.util.Checks;
import java.util.Map;

public final class ContextFrame {
  public final Std std;                // required
  public final Map<String, Object> ext; // optional

  public ContextFrame(Std std, Map<String, Object> ext) {
    this.std = Checks.notNull(std, "std");
    this.ext = (ext == null) ? Map.of() : Map.copyOf(ext);
  }

  public static final class Std {
    public final String contextId; // required
    public final boolean isEvent; // required
    public final String eventId; // optional

    public Std(String contextId, boolean isEvent, String eventId) {
      this.contextId = Checks.notBlank(contextId, "std.contextId");
      this.isEvent = isEvent;
      this.eventId = (eventId == null || eventId.isBlank()) ? null : eventId;
    }
  }
}
