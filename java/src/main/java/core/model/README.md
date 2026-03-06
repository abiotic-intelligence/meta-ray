# `core.model`

Protocol/domain objects shared across all Meta-ray implementations.

These classes are the **typed representation of on-the-wire messages and their payloads**.
They must be strict and deterministic so two implementations can interoperate reliably.

# core/model — Shared objects (protocol types)
Who uses it: 
- everyone.
Contains:
- domain message payloads (snapshot/action/assets)
- wire envelope types (WireMessage, MsgType, etc.)
- strict invariants
Dependency direction:
- model should be as dependency-free as possible
- api depends on model
- spi depends on model (or wire subpackage) as needed
The “flow”:
- Apps call api → receiver/emitter behavior
- Receiver/Emitter implementations use spi → to talk bytes/crypto/codecs
- Everyone shares model → to speak the same language

…and nothing in core imports jvm/android (ever).

## Files

### Session & discovery
- `Session.java` — negotiated session identity + selected control codec/security
- `EmitterConfig.java` — discovery result needed to connect

### Pull / context
- `PullOptions.java` — selector for `pullContext(contextId, opts)` (`latest` -> single frame, `all`/`limit(n)` -> lists)
- `ContextFrame.java` — full context state (keyframe), with `std.contextId` (required), `std.isEvent` (required), and `std.eventId` (optional)

### Push
- `ContextFrame.java` — multicast push event/state payload

### Assets
- `AssetDescriptor.java` — asset metadata (id, hash, fetch token/path, ttl)
- `AssetRegistration.java` — emitter-side input to register assets
- `AssetFetchRequest.java` — receiver-side request for asset bytes (by id/hash, optional ranges)
- `AssetBytes.java` — returned bytes (and optional range metadata)

### Actions
- `ActionRequest.java` — receiver-to-emitter action invocation (must support idempotency)
- `ActionResponse.java` — result/ack/error of an action

---

## Model invariants (must hold to “work”)

### Required fields must never be missing at runtime
If a field is required by the protocol, the constructor must enforce it.

**Working implementation requirement**
- Required strings must be non-null and non-blank.
- Required numeric fields must be within valid ranges.
- Collections must be non-null (use empty immutable collections when absent).

### Immutability and defensive copies
These types should be safe to share across threads and modules.

**Working implementation requirement**
- Fields are `final` and set only in constructors.
- Any input maps/lists are copied defensively (e.g., `Map.copyOf`, `List.copyOf`).

### Deterministic identity rules
Receivers and emitters must agree on caching/deduplication.

**Working implementation requirement**
- `Session.sessionId` is stable for the lifetime of the session.
- Asset caching must be anchored by `AssetDescriptor.hash` (e.g., `sha256:...`) when present.
- Actions must carry an `idempotencyKey` to prevent double execution on retries.

### Snapshot semantics
**Working implementation requirement**
- `ContextFrame` must be sufficient to render UI without needing earlier state.

---

## Strictness rules

- No “magic defaults” for required fields.
- No nullable required fields.
- No silent coercion (e.g., negative timestamps corrected to 0). Throw instead.

If a model instance cannot be represented validly on the wire, it should be impossible (or loudly failing) to construct.
