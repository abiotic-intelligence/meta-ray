package core.model.wire;

import core.util.Checks;

public enum MsgType {
  AUTH("auth"),

  PULL("pull"),

  CONTEXT_SNAPSHOT("context_snapshot"),
  ASSET_FETCH_REQUEST("asset_fetch_request"),
  ASSET_FETCH_RESPONSE("asset_fetch_response"),
  ACTION_REQUEST("action_request"),
  ACTION_RESPONSE("action_response"),
  ERROR("error");

  public final String wireName;

  MsgType(String wireName) {
    this.wireName = Checks.notBlank(wireName, "wireName");
  }

  @Override
  public String toString() {
    return wireName;
  }
}
