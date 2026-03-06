# Meta-ray Core (`core`)

`core` is the **protocol + public API** module.
It defines the interfaces and data types that all Meta-ray implementations share, without assuming any platform (Android/JVM) or any concrete network stack.

If something is “Meta-ray the protocol”, it lives here.

---

## Layout

- `build.gradle.kts`  
  Gradle config for the core module. Should stay lightweight (pure Java, minimal deps).

- `api/`  
  Public SDK interfaces (what app code programs against).

- `model/`  
  Protocol/domain objects exchanged between Receiver and Emitter (requests, responses, context, assets, session).

- `spi/`  
  Pluggable Service Provider Interfaces: transports, codec, crypto. Core depends on these abstractions, not implementations.

- `model/util/`  
  Small utilities used across core to enforce strictness and provide explicit failures.

# core/ — Contracts + protocol objects (Java)
Objects live here:
Public API interfaces: MetaRayReceiver, MetaRayEmitter
Protocol/domain models: ContextFrame, ActionRequest/Response, assets, session, etc.
Wire envelope models: WireMessage, MsgType, PullMessage, ErrorMessage
SPIs (interfaces for implementations): Codec, ControlTransport, MulticastTransport, CryptoProvider, ContextCache
Logic that lives here (allowed):
Fail-fast validation in constructors (Checks, strict invariants)
Routing helpers that are pure logic (e.g., MessageRouter mapping msg_type -> body class)
Schema docs + shared validators that don’t touch networking/OS
Logic that must NOT live here:
mDNS/DNS-SD discovery code
sockets, multicast join/leave, TLS handshakes
platform-specific threading/lifecycle
“default implementations” that require external libs (keep core dependency-light)

---

## `api/` — Public SDK Interfaces

### `MetaRayReceiver.java`
Defines the **Receiver** role: discover emitters, connect, pull context, fetch assets, receive multicast events, send actions.

Key expectations:
- Pull-first (`pullContext`) is the default.
- Receiver decides how to handle multicast events.
- Receiver closes resources explicitly (`close`).

### `MetaRayEmitter.java`
Defines the **Emitter** role: advertise, serve control channel, push context events, register assets, accept actions.

Key expectations:
- Emitter multicasts events "to whom it may concern".
- Emitter stores latest pushed context so receivers can pull at any time.

---

## `model/` — Protocol & Domain Objects

### `Session.java`
Represents a negotiated connection/session between Receiver and Emitter.
Holds session identity and negotiated control settings (`codec`, `security`).

### `EmitterConfig.java`
Discovery result describing how to connect to an emitter (addresses/ports/capabilities, etc.).
Used as input to `receiver.connect()`.

### `PullOptions.java`
Options for `pullContext(contextId, opts)` such as latest/all/limit for a specific context id.
`latest` maps to a single-frame receiver overload; `all/limit` map to list-returning pulls.

### `ContextFrame.java`
A **full** context state at a point in time (the “keyframe”).
Receiver can render a complete UI from this alone.

### `AssetDescriptor.java`
Metadata describing an interactive asset (image/gif), including cache identity (hash), fetch token/path, size hints, and TTL hints.

### `AssetRegistration.java`
Emitter-side request object used to register an asset into the emitter’s asset store and receive an `AssetDescriptor`.

### `AssetFetchRequest.java`
Receiver → Emitter request for asset bytes by `asset_id` or `hash`, optionally with range/chunk parameters.

### `AssetBytes.java`
The returned asset bytes (and any range metadata if chunking/ranges are used).
This is the “payload container” for binary asset data on the control channel.

### `ActionRequest.java`
Receiver → Emitter action invocation (tap/click/command).
Must support idempotency (prevent double execution on retries).

### `ActionResponse.java`
Emitter → Receiver response acknowledging the action and returning a result or structured error info.

---

## `spi/` — Pluggable Interfaces (Implementations Live Elsewhere)

### `Codec.java`
Encodes/decodes message objects to bytes (CBOR default, JSON optional).
Core should not hard-depend on a concrete codec library; implementations provide one.

### `ControlTransport.java`
Unicast request/response transport for:
- pullContext
- fetchAsset
- sendAction

Implementations use TLS in the current runtime; core only sees `request(payload)`.
Implementations use TLS in the current runtime; core expects async request/response via
`request(payload, timeout) -> CompletableFuture<byte[]>`.

### `MulticastTransport.java`
Multicast transport for context event frame notifications.
Emitter sends to the multicast channel; each receiver decides what to do with received events.

### `CryptoProvider.java`
Encrypt/decrypt primitives used by transports.
Encryption is mandatory: plaintext traffic is not a supported mode.

### `PacketHandler.java`
Generic callback interface for handling inbound packets/messages in transports.

### `ContextCache.java`
In-memory context cache contract with per-frame retention and context-id keyed list operations.

---

## `util/` — Strictness & Errors

### `Checks.java`
Fail-fast precondition utilities.
Used to enforce required fields and invariants early (no silent defaults for required data).

### `MetaRayException.java`
SDK-specific exception used for explicit, structured failures that are not just argument errors.
Use this when the system is in a valid state but an operation fails (protocol error, crypto error, transport error, etc.).

---

## Core rules (keep these boring and strict)

- No Android APIs, no platform code, no server frameworks.
- Prefer immutable models.
- Missing required fields should throw immediately.
- Unknown extensions (`ext`) are okay to ignore, but unknown **required standard fields** are not.

---

## Dependency boundaries

`core` may depend on:
- Java standard library only (plus test dependencies)

`core` must not depend on:
- Android SDK
- concrete mDNS implementations
- concrete TLS libraries
- concrete CBOR/JSON libs
