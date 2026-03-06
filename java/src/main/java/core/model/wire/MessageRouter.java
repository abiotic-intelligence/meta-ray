package core.model.wire;

import core.model.ActionRequest;
import core.model.ActionResponse;
import core.model.AssetBytes;
import core.model.AssetFetchRequest;
import core.model.ContextFrame;
import core.util.Checks;
import java.util.Map;

public final class MessageRouter {
  private MessageRouter() {}

  /** Returns the canonical body class for a given message type. */
  public static Class<?> bodyClassFor(MsgType t) {
    Checks.notNull(t, "msgType");
    return switch (t) {
      case AUTH -> AuthMessage.class;
      case PULL -> PullMessage.class;

      case CONTEXT_SNAPSHOT -> ContextFrame.class;

      case ASSET_FETCH_REQUEST -> AssetFetchRequest.class;
      case ASSET_FETCH_RESPONSE -> AssetBytes.class;

      case ACTION_REQUEST -> ActionRequest.class;
      case ACTION_RESPONSE -> ActionResponse.class;

      case ERROR -> ErrorMessage.class;
    };
  }

  /**
   * Validates ext namespace keys. Fail-fast: invalid namespaces are protocol errors.
   * Rule: reverse-domain style, contains at least one '.', no whitespace, no empty labels.
   */
  public static void validateExtNamespaces(Map<String, Object> ext) {
    if (ext == null || ext.isEmpty()) return;

    for (String key : ext.keySet()) {
      Checks.notBlank(key, "ext namespace key");
      if (containsWhitespace(key)) {
        throw new IllegalArgumentException("ext namespace contains whitespace: " + key);
      }
      int dot = key.indexOf('.');
      if (dot <= 0 || dot == key.length() - 1) {
        throw new IllegalArgumentException("ext namespace must be reverse-domain (contain a dot): " + key);
      }
      String[] parts = key.split("\\.");
      for (String p : parts) {
        if (p.isEmpty()) throw new IllegalArgumentException("ext namespace has empty label: " + key);
      }
    }
  }

  /** Ensures body is present for message types that require it (v0.1: all do). */
  public static void requireBody(Object body, MsgType t) {
    Checks.notNull(t, "msgType");
    if (body == null) {
      throw new IllegalArgumentException("std.body must not be null for msg_type=" + t.wireName);
    }
  }

  private static boolean containsWhitespace(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isWhitespace(s.charAt(i))) return true;
    }
    return false;
  }
}
