# `core.api`

Public, stable interfaces for Meta-ray.

Everything here is what application code (or platform adapters) programs against.
These interfaces define the behavioral contract of Receiver and Emitter.

## Files

- `MetaRayReceiver.java` - receiver role contract
- `MetaRayEmitter.java` - emitter role contract
- `InMemoryEmitterDirectory.java` - in-process emitter registration/discovery helper
- `InMemoryMetaRayEmitter.java` - reference in-memory emitter implementation
- `InMemoryMetaRayReceiver.java` - reference in-memory receiver implementation
- `ReceiverIdentity.java` - receiver identity record used to build auth headers
- `ReceiverIdentityProvider.java` - receiver identity + signing contract for auth flows
- `EphemeralReceiverIdentityProvider.java` - ephemeral receiver identity implementation
- `InstallScopedReceiverIdentityProvider.java` - persistent per-app-install identity bootstrap implementation

# `core/api` public SDK surface

Who uses it:
- apps and higher-level SDK layers

What lives here:
- method contracts and behavior rules
- minimal signature types from `core.model`

What does not live here:
- transport/crypto/codec implementation details
- platform-specific behavior (Android/JVM)

Dependency direction:
- `api` depends on `model`
- `api` should not depend on `spi` directly unless apps are expected to pass SPIs into constructors/factories

## Core contract: pull-first, multicast events

### Pull-first
Receivers must be able to obtain correct state via `pullContextAsync(contextId, opts)` at any time after `connectAsync()`.
Sync wrappers (`pullContext(contextId, opts)`, `connect()`) are retained for compatibility.

Working requirement:
- `pullContextAsync(contextId, PullOptions.latest())` returns latest `ContextFrame` (or `null` when empty).
- `pullContextAsync(contextId, opts)` returns a usable `List<ContextFrame>` for `all/limit`.

### Events are multicast and receiver-decided
Emitters send `ContextFrame` event payloads to the multicast channel.
Receivers decide whether to ignore, filter, or react to them.

Working requirement:
- `pushEventAsync()` multicasts a `ContextFrame`.
- Receivers remain pull-first for authoritative state.

## Strict error behavior

The API must not hide failures by returning nulls or partial objects.

Working requirement:
- invalid arguments -> `IllegalArgumentException`
- operational failures -> `MetaRayException`
- required return values are never `null`

## Threading and blocking expectations

APIs are async-first (`CompletableFuture`) and must be deadlock-safe and repeat-call-safe.
Sync wrappers exist for compatibility and should delegate to async methods.

Working requirement:
- `closeAsync()` / `shutdownAsync()` are idempotent
- `connectAsync()` returns a valid `Session` or fails
- `discoverAsync()` honors timeout and returns best-effort results

## Session correctness

Working requirement:
- `connectAsync()` returns a stable `Session.sessionId` (and sync wrapper `connect()` mirrors it)
- `Session.codec` and `Session.security` represent negotiated control-channel settings
- subsequent operations use that same session identity and negotiated settings

## What must not happen

- receiver ignores crypto/transport failures and returns success
- API returns empty placeholders for required data instead of throwing

## Implementation Status

- `core.api.InMemoryEmitterDirectory`
  - in-process emitter registration/discovery helper for core-only flows
- `core.api.InMemoryMetaRayEmitter`
  - reference emitter logic implementing pull-first state, multicast event send, asset registration/fetch, and action handling
- `core.api.InMemoryMetaRayReceiver`
  - reference receiver logic implementing discover/connect/pull, asset fetch, and actions
- `core.util.MetaRayException`
  - runtime exception for operational protocol/transport/crypto failures
