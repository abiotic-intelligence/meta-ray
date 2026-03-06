## Repository Modules

- `:core` -> `src/main/java/core`  
  Protocol models, public API contracts, SPIs.
- `:jvm` -> `src/main/java/jvm`  
  JVM runtime implementation.
- `:android` -> `src/main/java/android`  
  Android runtime implementation and platform helpers.
- `:testkit` -> `testkit`  
  Conformance/shared test scenarios.

## Build

```bash
./gradlew :core:build
./gradlew :jvm:build
./gradlew :android:assemble
```

## API Usage (JVM)

### Emitter setup

```java
import core.model.ContextFrame;
import core.model.EmitterConfig;
import io.metaray.jvm.runtime.LanMetaRayEmitter;
import java.util.Map;
import java.util.Set;

String emitterId = "3e9c8b2e-7e8c-4b4a-8b9a-3f0d6b6a1d11";
String appId = "netflix";
EmitterConfig emitterConfig = new EmitterConfig(
    1,
    "living-room-tv",
    appId,
    emitterId,
    null,
    "Living Room TV",
    "192.168.1.20",
    7777,
    "239.1.2.3",
    5001,
    Set.of("cbor", "json"),
    Set.of("tls"),
    Set.of("assets", "actions")
);

LanMetaRayEmitter emitter = new LanMetaRayEmitter(emitterConfig);
emitter.startControlServer();
emitter.startAdvertising();

// Push event/context frame to multicast listeners and update pull source.
emitter.pushEvent(new ContextFrame(
    new ContextFrame.Std("media", true, "evt-001"),
    Map.of("title", "Now Playing")
));
```

### 1) `auth` (unicast control)

```java
import core.model.EmitterConfig;
import core.model.Session;
import io.metaray.jvm.runtime.LanMetaRayReceiver;
import java.time.Duration;
import java.util.List;

LanMetaRayReceiver receiver = new LanMetaRayReceiver("netflix");
List<EmitterConfig> emitters = receiver.discover(Duration.ofSeconds(3));
Session session = receiver.connect(emitters.get(0));
```

Optional first-time pairing policy (evaluated per receiver request, not globally):

```java
emitter.setFirstPairingApprovalPolicy(req -> {
  // req.receiverKeyId / req.receiverId / req.receiverDeviceName are receiver-specific.
  boolean userAccepted = uiConfirmReceiver(req.receiverDeviceName, req.receiverKeyId);
  return userAccepted
      ? LanMetaRayEmitter.PairingApprovalDecision.ACCEPT
      : LanMetaRayEmitter.PairingApprovalDecision.DECLINE;
});
```

### Pairing Model (Per Receiver)

- First-time auth is receiver-specific (by `receiverKeyId`), not a one-time global switch.
- `setFirstPairingApprovalPolicy(...)` is called for each unknown receiver attempting `pair`.
- Pairing succeeds when either:
  - the receiver key is already registered (known receiver), or
  - the receiver is unknown and the per-request auth/pair decision is `ACCEPT`.
- After `ACCEPT`, that receiver key is persisted in emitter registry and future reconnects skip first-time pair.

JVM emitter callback example:

```java
emitter.setFirstPairingApprovalPolicy(req -> {
  boolean accepted = uiConfirmReceiver(req.receiverDeviceName, req.receiverKeyId);
  return accepted
      ? LanMetaRayEmitter.PairingApprovalDecision.ACCEPT
      : LanMetaRayEmitter.PairingApprovalDecision.DECLINE;
});
```

Android emitter callback example:

```java
emitter.setFirstPairingApprovalPolicy(req -> {
  boolean accepted = showPairDialog(req.receiverDeviceName, req.receiverKeyId);
  return accepted
      ? LanMetaRayEmitter.PairingApprovalDecision.ACCEPT
      : LanMetaRayEmitter.PairingApprovalDecision.DECLINE;
});
```

### 2) `pull` (unicast control, context-aware)

```java
import core.model.ContextFrame;
import core.model.PullOptions;
import java.util.List;

List<ContextFrame> allEvents = receiver.pullContext("events", PullOptions.all());
ContextFrame latestOffer = receiver.pullContext("offers", PullOptions.latest());
List<ContextFrame> lastTwoOffers = receiver.pullContext("offers", PullOptions.limit(2));

if (latestOffer != null) {
  // consume latest frame
}
```

### 3) `push` (multicast events)

```java
import core.model.ContextFrame;
import core.model.PullOptions;
import java.util.List;
import java.util.Map;

emitter.pushEvent(new ContextFrame(
    new ContextFrame.Std("events", true, "evt-101"),
    Map.of("title", "Deal unlocked")
));
```

## InMemoryContextCache Usage

Use cache helpers to keep `ContextFrame` lists keyed by `contextId`.
Retention is per frame entry (`retentionTimeInMs`), not per whole list.

### JVM

```java
import core.model.ContextFrame;
import io.metaray.jvm.cache.InMemoryContextCache;
import java.util.List;

InMemoryContextCache cache = new InMemoryContextCache("events", List.of());
cache.insert("events", latest, 120_000); // keep this frame for 2 minutes

for (ContextFrame frame : cache.getList("events")) {
  // consume cached frames
}
```

Multiple context IDs in the same cache:

```java
import core.model.ContextFrame;
import io.metaray.jvm.cache.InMemoryContextCache;
import java.util.List;

InMemoryContextCache cache = new InMemoryContextCache("events", List.of());
cache.insert(
    "events",
    new ContextFrame(new ContextFrame.Std("promotion", true, null), java.util.Map.of("title", "Deal")),
    60_000
);
cache.insert(
    "offers",
    new ContextFrame(new ContextFrame.Std("commerce", true, null), java.util.Map.of("offerId", "abc")),
    60_000
);

List<ContextFrame> events = cache.getList("events");
cache.clearList("offers");
```

### Android

```java
import core.model.ContextFrame;
import io.metaray.android.cache.InMemoryContextCache;
import java.util.List;

InMemoryContextCache cache = new InMemoryContextCache("events", List.of());
cache.insert("events", latest, 120_000);
```

## Receiver Registry (Known Receivers)

If you already trust a receiver key, pre-register it on the emitter to skip first-time pair approval and allow auto-reconnect flow (`hello -> challenge -> proof -> ok`).

### JVM emitter

```java
import core.api.InstallScopedReceiverIdentityProvider;

InstallScopedReceiverIdentityProvider identity =
    new InstallScopedReceiverIdentityProvider("netflix");

emitter.registerReceiver(
    identity.currentKeyId(),
    identity.currentReceiverId(),
    identity.currentPublicKey()
);

boolean known = emitter.isReceiverRegistered(identity.currentKeyId());
```

### Android emitter

```java
import core.api.InstallScopedReceiverIdentityProvider;

InstallScopedReceiverIdentityProvider identity =
    new InstallScopedReceiverIdentityProvider("netflix");

emitter.registerReceiver(
    identity.currentKeyId(),
    identity.currentReceiverId(),
    identity.currentPublicKey()
);
```

## API Usage (Android)

### Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

### Android emitter setup

```java
import core.model.ContextFrame;
import core.model.EmitterConfig;
import io.metaray.android.discovery.NsdAdvertiser;
import io.metaray.android.runtime.LanMetaRayEmitter;
import java.util.Map;
import java.util.Set;

NsdAdvertiser advertiser = new NsdAdvertiser(context);

String emitterId = "3e9c8b2e-7e8c-4b4a-8b9a-3f0d6b6a1d11";
String appId = "netflix";
EmitterConfig emitterConfig = new EmitterConfig(
    1,
    "android-tv-1",
    appId,
    emitterId,
    null,
    "Living Room Android TV",
    "192.168.1.30",
    7777,
    "239.1.2.3",
    5001,
    Set.of("cbor", "json"),
    Set.of("tls"),
    Set.of("assets", "actions")
);

LanMetaRayEmitter emitter = new LanMetaRayEmitter(emitterConfig, advertiser);
emitter.startControlServer();
emitter.startAdvertising();

// push event over MEC
emitter.pushEvent(new ContextFrame(
    new ContextFrame.Std("promotion", true, null),
    Map.of("title", "30% off")
));
```

### Android receiver setup

```java
import core.model.EmitterConfig;
import core.model.Session;
import io.metaray.android.runtime.LanMetaRayReceiver;
import java.time.Duration;
import java.util.List;

LanMetaRayReceiver receiver = new LanMetaRayReceiver(context, "netflix");
List<EmitterConfig> emitters = receiver.discover(Duration.ofSeconds(3));
Session session = receiver.connect(emitters.get(0));
```

Custom receiver with persistent identity + explicit multicast lock manager (recommended):

```java
import core.api.InstallScopedReceiverIdentityProvider;
import io.metaray.android.codec.JacksonJsonCodec;
import io.metaray.android.discovery.NsdDiscovery;
import io.metaray.android.net.WifiMulticastLockManager;
import io.metaray.android.runtime.LanMetaRayReceiver;

LanMetaRayReceiver receiver = new LanMetaRayReceiver(
    new NsdDiscovery(context, NsdDiscovery.DEFAULT_SERVICE_TYPE, "netflix"),
    null,
    new JacksonJsonCodec(),
    null,
    new InstallScopedReceiverIdentityProvider("netflix"),
    new WifiMulticastLockManager(context, "metaray-android-multicast"),
    EmitterConfig -> true
);
```
