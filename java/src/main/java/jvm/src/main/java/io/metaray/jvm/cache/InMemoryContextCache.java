package io.metaray.jvm.cache;

import core.model.ContextFrame;
import core.spi.ContextCache;
import core.util.Checks;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryContextCache implements ContextCache, AutoCloseable {
  private final Object lock = new Object();
  private final Map<String, List<Entry<ContextFrame>>> framesByContextId = new java.util.HashMap<>();
  private final ScheduledExecutorService evictionExecutor;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  public InMemoryContextCache(String contextId, List<ContextFrame> frames) {
    this.evictionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread t = new Thread(runnable, "metaray-jvm-context-cache");
      t.setDaemon(true);
      return t;
    });

    String id = Checks.notBlank(contextId, "contextId");
    List<ContextFrame> initial = (frames == null) ? List.of() : List.copyOf(frames);
    List<Entry<ContextFrame>> entries = new ArrayList<>(initial.size());
    for (ContextFrame frame : initial) {
      entries.add(new Entry<>(Checks.notNull(frame, "frame"), Long.MAX_VALUE));
    }
    synchronized (lock) {
      framesByContextId.put(id, entries);
    }
  }

  @Override
  public void insert(String contextId, ContextFrame frame, long retentionTimeInMs) {
    ensureOpen();
    String id = Checks.notBlank(contextId, "contextId");
    ContextFrame value = Checks.notNull(frame, "frame");
    long retentionMs = Checks.nonNegative(retentionTimeInMs, "retentionTimeInMs");

    Entry<ContextFrame> entry = new Entry<>(value, expiresAtNanos(retentionMs));
    synchronized (lock) {
      List<Entry<ContextFrame>> entries = framesByContextId.computeIfAbsent(id, ignored -> new ArrayList<>());
      purgeExpiredLocked(entries, System.nanoTime());
      entries.add(entry);
    }
    scheduleEviction(id, entry, retentionMs);
  }

  @Override
  public List<ContextFrame> getList(String contextId) {
    ensureOpen();
    String id = Checks.notBlank(contextId, "contextId");
    synchronized (lock) {
      List<Entry<ContextFrame>> entries = framesByContextId.get(id);
      if (entries == null || entries.isEmpty()) {
        return List.of();
      }
      purgeExpiredLocked(entries, System.nanoTime());
      if (entries.isEmpty()) {
        framesByContextId.remove(id);
        return List.of();
      }
      List<ContextFrame> out = new ArrayList<>(entries.size());
      for (Entry<ContextFrame> entry : entries) {
        out.add(entry.frame);
      }
      return List.copyOf(out);
    }
  }

  @Override
  public void clearList(String contextId) {
    ensureOpen();
    String id = Checks.notBlank(contextId, "contextId");
    synchronized (lock) {
      framesByContextId.remove(id);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      synchronized (lock) {
        framesByContextId.clear();
      }
      evictionExecutor.shutdownNow();
    }
  }

  private void scheduleEviction(String contextId, Entry<ContextFrame> entry, long retentionMs) {
    try {
      evictionExecutor.schedule(() -> removeEntry(contextId, entry), retentionMs, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      throw new IllegalStateException("Context cache eviction scheduler rejected eviction", e);
    }
  }

  private void removeEntry(String contextId, Entry<ContextFrame> entry) {
    synchronized (lock) {
      List<Entry<ContextFrame>> entries = framesByContextId.get(contextId);
      if (entries == null) return;
      entries.remove(entry);
      if (entries.isEmpty()) {
        framesByContextId.remove(contextId);
      }
    }
  }

  private static void purgeExpiredLocked(List<Entry<ContextFrame>> entries, long nowNanos) {
    Iterator<Entry<ContextFrame>> iterator = entries.iterator();
    while (iterator.hasNext()) {
      Entry<ContextFrame> entry = iterator.next();
      if (isExpired(entry, nowNanos)) {
        iterator.remove();
      }
    }
  }

  private static boolean isExpired(Entry<?> entry, long nowNanos) {
    return nowNanos - entry.expiresAtNanos >= 0;
  }

  private static long expiresAtNanos(long retentionMs) {
    long now = System.nanoTime();
    long delta = toNanosSaturated(retentionMs);
    if (Long.MAX_VALUE - now < delta) {
      return Long.MAX_VALUE;
    }
    return now + delta;
  }

  private static long toNanosSaturated(long millis) {
    if (millis <= 0) return 0L;
    long maxMsBeforeOverflow = Long.MAX_VALUE / 1_000_000L;
    if (millis > maxMsBeforeOverflow) return Long.MAX_VALUE;
    return millis * 1_000_000L;
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("InMemoryContextCache is closed");
    }
  }

  private static final class Entry<T> {
    final T frame;
    final long expiresAtNanos;

    Entry(T frame, long expiresAtNanos) {
      this.frame = frame;
      this.expiresAtNanos = expiresAtNanos;
    }
  }
}
