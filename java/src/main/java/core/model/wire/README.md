# `core.model.wire`

Wire-envelope model for Meta-ray control/data messages.

## Top-level shape

All wire payloads use:

```json
{
  "std": {
    "v": 1,
    "msgType": "pull",
    "header": {
      "appId": "netflix",
      "id": "uuid",
      "sessionId": "session-token",
      "keyId": "sha256:receiver-key-id",
      "deviceName": "Living Room TV"
    },
    "body": {
      "contextId": "offers",
      "mode": "LIMIT",
      "limit": 50
    }
  },
  "ext": {}
}
```

Core classes:
- `WireMessage`
- `WireMessage.Std`
- `MsgType`
- `MessageRouter`
- `SchemaRegistry` / `DefaultSchemaRegistry`

## Control message bodies

Session bootstrap + negotiation:
- `AuthMessage` (`msgType=auth`, step-based handshake)
  - steps used by runtime handshake: `hello`, `pairRequired`, `pair`, `challenge`, `proof`, `ok`, `deny`

Standard pull/action/asset/error bodies:
- `PullMessage` (`msgType=pull` for request/response, includes `contextId`, `mode` (`LATEST|ALL|LIMIT`), optional `limit` for `LIMIT`, and response `frames`)
- `ErrorMessage`
- plus domain bodies mapped in `MessageRouter`

Multicast push payloads are encrypted `ContextFrame` objects and are not encoded as control `WireMessage` envelopes.

## Notes

- `DefaultSchemaRegistry` validates required standard fields and extension namespace rules.
- `WireMessage.Header.deviceName` is optional (present when relevant for UX/prompts).
- Unknown extension namespaces are passed through; unknown/invalid required fields fail fast.
