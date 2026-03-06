# Meta-ray JVM (`jvm`)

`jvm` contains desktop/server implementations and tools for Meta-ray.
This is the actual networking and runtime behavior for JVM targets.
Module root in this repository is `src/main/java/jvm`.

## Files

- `build.gradle.kts`
  JVM module build config. Depends on `:core`.
- `src/main/java/io/metaray/jvm/package-info.java`
  Package documentation placeholder.

# `jvm` implementation scope

Concrete networking implementations:
- mDNS discovery (or manual discovery fallback)
- control channel (TLS, framing, request/response)
- multicast channel (UDP group join, send/recv)
- concrete crypto plumbing implementing `CryptoProvider`
- concrete codecs implementing `Codec` (CBOR/JSON)
- optional tools: emitter simulator, packet logger/replay

Objects that live here:
- platform-specific configuration objects (ports, timeouts)
- implementation classes (`TlsControlTransport`, `UdpMulticastTransport`, etc.)
- internal helpers (threading, executors)

Does not belong here:
- protocol model definitions (those stay in `core`)

## What should live here

### Discovery implementation
mDNS/DNS-SD service discovery (or alternatives) that outputs `EmitterConfig`.

### Control channel implementation
Concrete `ControlTransport`:
- request/response framing
- secure channel (TLS)
- session open with negotiated codec/security selection

### Multicast implementation
Concrete `MulticastTransport`:
- join/leave groups
- receive datagrams
- send notifications to the multicast channel

### Crypto implementation
Concrete `CryptoProvider`:
- group AEAD for multicast payloads
- control channel crypto integration

### Dev tools / simulators (optional)
- emitter simulator for local development
- packet capture/replay utilities
- fake network harness for manual debugging

## Implementation Status

- Discovery implementation:
  - `io.metaray.jvm.discovery.JmDnsDiscovery` (mDNS/DNS-SD discovery)
  - `io.metaray.jvm.discovery.JmDnsAdvertiser` (mDNS/DNS-SD advertising)
  - `io.metaray.jvm.discovery.ManualDiscovery` (manual fallback discovery helper)
- Control channel implementations:
  - `io.metaray.jvm.transport.TlsControlServer`
  - `io.metaray.jvm.transport.TlsControlTransport`
  - `io.metaray.jvm.transport.TcpControlServer` (available utility, not used by `LanMetaRay` runtime flow)
  - `io.metaray.jvm.transport.TcpControlTransport` (available utility, not used by `LanMetaRay` runtime flow)
- Multicast implementation:
  - `io.metaray.jvm.transport.UdpMulticastTransport`
- CryptoProvider implementation:
  - `io.metaray.jvm.crypto.AesGcmCryptoProvider`
- Codec implementations:
  - `io.metaray.jvm.codec.JacksonJsonCodec`
  - `io.metaray.jvm.codec.JacksonCborCodec`
- In-memory context cache implementations:
  - `io.metaray.jvm.cache.InMemoryContextCache`
- Internal threading helpers:
  - `io.metaray.jvm.internal.JvmExecutors`
- JVM-specific configuration:
  - `io.metaray.jvm.config.JvmTransportConfig`
- Concrete runtime API implementations:
  - `io.metaray.jvm.runtime.LanMetaRayEmitter`
  - `io.metaray.jvm.runtime.LanMetaRayReceiver`
  - `io.metaray.jvm.runtime.ControlWire` (WireMessage envelope encode/decode helper)

Control runtime behavior:
- Control requests/responses use `core.model.wire.WireMessage` + `MsgType` envelopes.
- Connect performs auth handshake:
  `hello -> (pairRequired -> pair -> challenge | challenge) -> proof -> (ok | deny)`.
- First-time pairing decisions are app-layer policy:
  runtime exposes `LanMetaRayEmitter#setFirstPairingApprovalPolicy(...)`.
  The callback is invoked per receiver pairing request (`receiverKeyId`/`receiverId`/`receiverDeviceName`).
  Pairing succeeds only when receiver key is already known, or unknown receiver auth/pair is explicitly accepted.
  Accepted receivers are stored per key in emitter receiver registry.
- Known receiver keys skip pairing and continue on auto-reconnect (`hello -> challenge -> proof -> ok`).
- Receiver sends ordered codec/security preferences in auth `ext`.
- `auth/ok` returns `sessionId` plus selected codec/security in `ext`, and post-auth control messages use that negotiated codec.
- Runtime requires TLS for control; raw TCP control is not supported.
- Context event frames are multicast without control-channel opt-in.
- Push payloads are encoded `ContextFrame` objects (JSON codec) before multicast encryption.
- TLS mode requires JVM keystore/truststore configuration for successful handshake.

## Non-negotiables

- No Android APIs.
- Protocol types stay in `:core`. This module implements SPIs and runtime behavior only.

## Build

- `./gradlew :jvm:build`
- `./gradlew :jvm:test` (includes `testkit`-backed conformance scenarios for codec parity and malformed-map fuzz)
