package io.metaray.jvm.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.ContextFrame;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

final class InMemoryContextCacheTest {
  @Test
  void constructorSeedsInitialContextList() {
    ContextFrame initial = frame("initial");
    try (InMemoryContextCache cache = new InMemoryContextCache("events", List.of(initial))) {
      assertEquals(1, cache.getList("events").size());
      assertEquals("initial", cache.getList("events").get(0).std.contextId);
    }
  }

  @Test
  void insertAndReadByContextId() {
    try (InMemoryContextCache cache = new InMemoryContextCache("events", List.of())) {
      cache.insert("events", frame("promotion"), 1_000L);
      cache.insert("offers", frame("commerce"), 1_000L);

      assertEquals(1, cache.getList("events").size());
      assertEquals("promotion", cache.getList("events").get(0).std.contextId);
      assertEquals(1, cache.getList("offers").size());
      assertEquals("commerce", cache.getList("offers").get(0).std.contextId);
    }
  }

  @Test
  void retentionIsPerFrameEntry() throws InterruptedException {
    try (InMemoryContextCache cache = new InMemoryContextCache("events", List.of())) {
      cache.insert("events", frame("short-lived"), 20L);
      cache.insert("events", frame("long-lived"), 1_000L);

      awaitTrue(() -> cache.getList("events").size() == 1, 1_000L);
      assertEquals("long-lived", cache.getList("events").get(0).std.contextId);
    }
  }

  @Test
  void clearListRemovesOnlyTargetContextId() {
    try (InMemoryContextCache cache = new InMemoryContextCache("events", List.of())) {
      cache.insert("events", frame("e1"), 1_000L);
      cache.insert("offers", frame("o1"), 1_000L);

      cache.clearList("events");

      assertTrue(cache.getList("events").isEmpty());
      assertEquals(1, cache.getList("offers").size());
    }
  }

  private static ContextFrame frame(String context) {
    return new ContextFrame(
        new ContextFrame.Std(context, true, null),
        Map.of("title", context)
    );
  }

  private static void awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
    long start = System.nanoTime();
    long timeoutNanos = timeoutMs * 1_000_000L;
    while (System.nanoTime() - start < timeoutNanos) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(10L);
    }
    assertTrue(condition.getAsBoolean(), "Condition did not become true within timeout");
  }
}
