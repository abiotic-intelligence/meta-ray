package core.api;

import core.model.EmitterConfig;
import core.util.MetaRayException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryEmitterDirectory {
  private final ConcurrentMap<String, InMemoryMetaRayEmitter> emittersByInstanceId = new ConcurrentHashMap<>();

  public void register(InMemoryMetaRayEmitter emitter) {
    Objects.requireNonNull(emitter, "emitter must not be null");
    String id = emitter.info().instanceId;
    InMemoryMetaRayEmitter previous = emittersByInstanceId.putIfAbsent(id, emitter);
    if (previous != null && previous != emitter) {
      throw new IllegalArgumentException("Emitter instance already registered: " + id);
    }
  }

  public void unregister(InMemoryMetaRayEmitter emitter) {
    Objects.requireNonNull(emitter, "emitter must not be null");
    emittersByInstanceId.remove(emitter.info().instanceId, emitter);
  }

  public List<EmitterConfig> discover(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be >= 0");
    }
    return emittersByInstanceId.values().stream()
        .filter(InMemoryMetaRayEmitter::isAdvertising)
        .map(InMemoryMetaRayEmitter::info)
        .toList();
  }

  InMemoryMetaRayEmitter resolve(EmitterConfig info) {
    Objects.requireNonNull(info, "emitter info must not be null");
    InMemoryMetaRayEmitter emitter = emittersByInstanceId.get(info.instanceId);
    if (emitter == null) {
      throw new MetaRayException("Emitter not found: " + info.instanceId);
    }
    return emitter;
  }
}
