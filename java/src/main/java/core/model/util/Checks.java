package core.util;

import java.util.Objects;

public final class Checks {
  private Checks() {}

  public static <T> T notNull(T v, String name) {
    return Objects.requireNonNull(v, name + " must not be null");
  }

  public static String notBlank(String v, String name) {
    notNull(v, name);
    if (v.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return v;
  }

  public static long nonNegative(long v, String name) {
    if (v < 0) throw new IllegalArgumentException(name + " must be >= 0");
    return v;
  }

  public static int exact(int v, int expected, String name) {
    if (v != expected) throw new IllegalArgumentException(name + " must be " + expected);
    return v;
  }
}