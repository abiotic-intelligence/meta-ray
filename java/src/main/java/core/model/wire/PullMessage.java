package core.model.wire;

import core.model.ContextFrame;
import core.model.PullOptions;
import core.util.Checks;
import java.util.List;

public final class PullMessage {
  public final String contextId; // request required
  public final PullOptions.Mode mode; // request selector
  public final Integer limit; // optional request hint
  public final List<ContextFrame> frames; // response payload

  public PullMessage(String contextId, PullOptions.Mode mode, Integer limit, List<ContextFrame> frames) {
    this.contextId = Checks.notBlank(contextId, "contextId");
    this.mode = Checks.notNull(mode, "mode");
    if (this.mode == PullOptions.Mode.LIMIT) {
      if (limit == null || limit <= 0) {
        throw new IllegalArgumentException("limit must be greater than 0 when mode=LIMIT");
      }
    } else if (limit != null) {
      throw new IllegalArgumentException("limit must be null unless mode=LIMIT");
    }
    this.limit = limit;
    this.frames = (frames == null) ? List.of() : List.copyOf(frames);
    for (ContextFrame frame : this.frames) {
      Checks.notNull(frame, "frames element");
    }
  }
}
