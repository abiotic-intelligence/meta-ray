# `core.spi`

Service Provider Interfaces (SPIs) for Meta-ray.

These interfaces are the extension points where platform modules (`android`, `jvm`) plug in concrete implementations.
`core` depends on these abstractions so it can stay transport/codec/crypto agnostic.

## Files

- `Codec.java`
- `ControlTransport.java`
- `MulticastTransport.java`
- `CryptoProvider.java`
- `PacketHandler.java`
- `ContextCache.java`

# core/spi — Pluggable interfaces (implementer-facing)
Who uses it: 
- platform modules (jvm, android) that implement the protocol.
What it contains: 
 - extension points:
 - Codec (encode/decode)
 - ControlTransport (unicast request/response)
 - MulticastTransport (multicast send/recv)
 - CryptoProvider (seal/open)
 - PacketHandler (callbacks)
 - ContextCache (in-memory context frame retention API)
What lives here (allowed):
- Pure interfaces and tiny helper types (like handlers)
- Very strict behavioral expectations (timeouts, fail-fast decoding)
What must NOT live here:
- concrete TLS/mDNS/socket code
- dependencies on any specific library
Dependency direction:
- spi may reference model types if needed (e.g., Codec might encode WireMessage<?>)
- jvm and android depend on spi to implement it

## What each SPI must do

### `Codec`
Encodes/decodes message objects to/from bytes (CBOR default; JSON optional).

Working requirements:
- `encode()` is deterministic for the same input object.
- `decode()` rejects malformed input, missing required fields, and type mismatches.

### `ControlTransport`
Encrypted unicast request/response channel for pulls, asset fetch, and actions.

Working requirements:
- `request(payload, timeout)` is asynchronous (`CompletableFuture`) and non-blocking by default.
- It must provide bounded timeouts and request/response correlation.
- Transport failures surface as exceptions (never swallowed).
- `close()` releases all resources.

### `MulticastTransport`
UDP multicast event channel (Emitter -> many).

Working requirements:
- Supports join/leave multicast group.
- Sends datagrams asynchronously.
- Receives datagrams via `onPacket(handler)`.
- Handler delivery threading behavior is documented by each implementation.
- Receive/send setup failures surface explicitly.
- `close()` releases all resources.

### `CryptoProvider`
Encrypt/decrypt primitive used by transports.

Working requirements:
- `seal(plaintext)` returns ciphertext that contains everything needed to decrypt.
- `open(ciphertext)` verifies integrity/authenticity and throws on auth failure.
- No optional plaintext mode.

### `PacketHandler`
Callback used by transports to deliver inbound packets/messages.

Working requirements:
- Should not block indefinitely.
- Threading model must be documented by transport implementations.

### `ContextCache`
In-memory context frame cache contract for retaining frames by `contextId` over a per-insert retention window.

Working requirements:
- `insert(contextId, frame, retentionTimeInMs)` stores a frame and expires that frame after the provided retention.
- `getList(contextId)` returns the current frame list for that context id.
- `clearList(contextId)` drops all retained frames only for that context id.

## Failure behavior

All SPIs are fail-fast:
- invalid input -> throw
- crypto/auth failure -> throw
- malformed payload -> throw
- timeout/unreachable -> throw
