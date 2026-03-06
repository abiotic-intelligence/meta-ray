package core.model;

import core.util.Checks;
import java.util.Map;

public final class ActionRequest {
  public final String actionId;
  public final String idempotencyKey;
  public final Map<String, Object> params;
  public final String receiverId; // optional (nullable by convention only)

  public ActionRequest(String actionId, String idempotencyKey, Map<String, Object> params, String receiverId) {
    this.actionId = Checks.notBlank(actionId, "actionId");
    this.idempotencyKey = Checks.notBlank(idempotencyKey, "idempotencyKey");
    this.params = (params == null) ? Map.of() : Map.copyOf(params);
    this.receiverId = receiverId;
  }
}