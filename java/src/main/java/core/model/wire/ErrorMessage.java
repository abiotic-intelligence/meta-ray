package core.model.wire;

import core.util.Checks;
import java.util.Map;

public final class ErrorMessage {
  public final String errorCode;            // required
  public final String errorMsg;             // required
  public final Map<String, Object> details; // optional

  public final String failedMsgType;        // optional (wire string)
  public final Long failedSeq;              // optional

  public ErrorMessage(
      String errorCode,
      String errorMsg,
      Map<String, Object> details,
      String failedMsgType,
      Long failedSeq
  ) {
    this.errorCode = Checks.notBlank(errorCode, "errorCode");
    this.errorMsg = Checks.notBlank(errorMsg, "errorMsg");
    this.details = (details == null) ? Map.of() : Map.copyOf(details);

    this.failedMsgType = (failedMsgType == null || failedMsgType.isBlank()) ? null : failedMsgType;
    this.failedSeq = (failedSeq == null) ? null : Checks.nonNegative(failedSeq, "failedSeq");
  }
}