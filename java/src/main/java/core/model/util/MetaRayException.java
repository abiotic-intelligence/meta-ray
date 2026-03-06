package core.util;

public final class MetaRayException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public MetaRayException(String message) {
    super(message);
  }

  public MetaRayException(String message, Throwable cause) {
    super(message, cause);
  }
}
