package core.model;

import core.util.Checks;
import java.util.Map;

public final class ActionResponse {
  public enum Status { OK, REJECTED, ERROR }

  public final Status status;
  public final Map<String, Object> result;   // optional, immutable (empty if absent)
  public final String errorCode;             // optional
  public final String errorMsg;              // optional (can be localized)

  public ActionResponse(Status status, Map<String, Object> result, String errorCode, String errorMsg) {
    this.status = Checks.notNull(status, "status");
    this.result = (result == null) ? Map.of() : Map.copyOf(result);
    this.errorCode = blankToNull(errorCode);
    this.errorMsg = blankToNull(errorMsg);

    // Fail-fast invariants to avoid ambiguous states.
    if (this.status == Status.OK) {
      if (this.errorCode != null || this.errorMsg != null) {
        throw new IllegalArgumentException("errorCode/errorMsg must be null when status=OK");
      }
    } else {
      // REJECTED or ERROR must carry at least some explanation.
      if (this.errorCode == null && this.errorMsg == null) {
        throw new IllegalArgumentException("errorCode or errorMsg must be provided when status=" + this.status);
      }
    }
  }

  public static ActionResponse ok(Map<String, Object> result) {
    return new ActionResponse(Status.OK, result, null, null);
  }

  public static ActionResponse rejected(String errorCode, String errorMsg) {
    return new ActionResponse(Status.REJECTED, null, errorCode, errorMsg);
  }

  public static ActionResponse error(String errorCode, String errorMsg) {
    return new ActionResponse(Status.ERROR, null, errorCode, errorMsg);
  }

  private static String blankToNull(String s) {
    if (s == null) return null;
    return s.isBlank() ? null : s;
  }
}