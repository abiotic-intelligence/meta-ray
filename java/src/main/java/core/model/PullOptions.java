package core.model;

public class PullOptions {
  public enum Mode {
    LATEST,
    ALL,
    LIMIT
  }

  public final Mode mode;
  public final Integer limit;

  protected PullOptions(Mode mode, Integer limit) {
    this.mode = mode;
    this.limit = limit;
  }

  public static final class Latest extends PullOptions {
    private Latest() {
      super(Mode.LATEST, null);
    }
  }

  public static final class All extends PullOptions {
    private All() {
      super(Mode.ALL, null);
    }
  }

  public static final class Limit extends PullOptions {
    private Limit(int limit) {
      super(Mode.LIMIT, limit);
    }
  }

  public static Latest latest() {
    return new Latest();
  }

  public static All all() {
    return new All();
  }

  public static Limit limit(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be greater than 0");
    }
    return new Limit(limit);
  }
}
