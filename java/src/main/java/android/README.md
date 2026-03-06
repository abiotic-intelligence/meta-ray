# Meta-ray Android (`android`)

`android` is the **Android-only adapter layer** for Meta-ray.
It exists so `core` stays pure Java and never imports Android APIs.
Module root in this repository is `src/main/java/android`.

If a class uses:
- `android.net.*`
- `android.net.wifi.*`
- lifecycle / services
- Android permission / connectivity behaviors

…it belongs here.

---

# android/ — Android implementation layer (helpers + platform wiring)
Logic lives here:
- Android Wi-Fi multicast lock handling (lifecycle-safe)
- Android discovery helpers / wrappers
Android implementations of:
- ControlTransport
- MulticastTransport
- CryptoProvider
- Codec (if you want on-device encode/decode)
- Main-thread safety / background threading wrappers
Objects that live here:
- Android-specific config and lifecycle helpers
- Implementation classes
Does NOT belong here:
- protocol model definitions (stay in core)
- JVM/server tooling (that’s jvm)

## Files

- `build.gradle.kts`  
  Android library build config (compileSdk/minSdk, desugaring, etc.).
  This module depends on `:core`.

- `src/main/AndroidManifest.xml`  
  Minimal manifest for an Android library module.
  Keep it lean; only declare permissions/components if the module truly requires them.

- `src/main/java/io/metaray/android/package-info.java`  
  Package documentation placeholder.
  Use it to document Android-specific behaviors and constraints (multicast lock requirements, power optimizations, etc.).

---

### Android discovery helpers
Android-friendly wrapper around discovery mechanisms (mDNS/DNS-SD or fallback).
Goal: produce `EmitterConfig` objects for `MetaRayReceiver.connect()`.

### Wi-Fi multicast helpers
Helpers to safely acquire/release `WifiManager.MulticastLock` when joining multicast groups.
Must be lifecycle-safe and never leak locks.

### Android transport implementations
Concrete implementations of core SPIs for Android:
- `ControlTransport` (TLS on Android)
- `MulticastTransport` (UDP multicast join/leave/send/receive)
- `CryptoProvider` glue for Android-supported crypto primitives

### Threading & lifecycle adapters
Anything that prevents running network I/O on the main thread and marshals callbacks correctly.

## Implementation Status

- Android discovery helpers:
  - `io.metaray.android.discovery.ManualDiscovery` (manual fallback discovery helper)
  - `io.metaray.android.discovery.NsdDiscovery` (Android mDNS/DNS-SD discovery helper)
  - `io.metaray.android.discovery.NsdAdvertiser` (Android mDNS/DNS-SD advertising helper)
- Wi-Fi multicast helpers:
  - `io.metaray.android.net.WifiMulticastLockManager` (lifecycle-safe multicast lock acquire/release)
- Android transport implementations:
  - `io.metaray.android.transport.TlsControlServer`
  - `io.metaray.android.transport.TlsControlTransport`
  - `io.metaray.android.transport.UdpMulticastTransport`
  - `io.metaray.android.transport.TcpControlServer` (available utility, not used by `LanMetaRay` runtime flow)
  - `io.metaray.android.transport.TcpControlTransport` (available utility, not used by `LanMetaRay` runtime flow)
- CryptoProvider implementation:
  - `io.metaray.android.crypto.AesGcmCryptoProvider`
- Codec implementations:
  - `io.metaray.android.codec.JacksonJsonCodec`
  - `io.metaray.android.codec.JacksonCborCodec`
- In-memory context cache implementations:
  - `io.metaray.android.cache.InMemoryContextCache`
- Threading & internal helpers:
  - `io.metaray.android.internal.AndroidExecutors`
- Android-specific configuration:
  - `io.metaray.android.config.AndroidTransportConfig`
- Concrete runtime API implementations:
  - `io.metaray.android.runtime.LanMetaRayEmitter`
  - `io.metaray.android.runtime.LanMetaRayReceiver`
  - `io.metaray.android.runtime.ControlWire` (WireMessage envelope encode/decode helper)

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
- `LanMetaRayReceiver(Context...)` enables multicast lock support by default for multicast reliability on real devices.
- TLS mode requires proper device/app trust material and server certificate setup.

---

## Non-negotiables

- Do **not** move protocol models into this module.
- This module must not redefine protocol meaning; it only adapts it to Android realities.
- Keep Android-specific quirks documented (OEM power saving, multicast reliability, permissions).

---

## Build

- `./gradlew :android:assemble`
- `./gradlew :android:testDebugUnitTest` (includes `testkit`-backed conformance scenarios for codec parity and malformed-map fuzz)

Windows note (if `testDebugUnitTest` fails to launch the test JVM due broken `PATH` parsing):

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path='C:\Program Files\Java\jdk-17\bin;C:\Windows\System32;C:\Windows'
.\gradlew.bat :android:testDebugUnitTest --no-daemon
```
