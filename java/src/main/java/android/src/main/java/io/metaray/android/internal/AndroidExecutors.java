package io.metaray.android.internal;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class AndroidExecutors {
  private AndroidExecutors() {}

  public static ExecutorService newCachedIoExecutor(String threadPrefix) {
    return Executors.newCachedThreadPool(daemonFactory(threadPrefix));
  }

  private static ThreadFactory daemonFactory(String threadPrefix) {
    Objects.requireNonNull(threadPrefix, "threadPrefix must not be null");
    AtomicLong seq = new AtomicLong(0);
    return runnable -> {
      Thread t = new Thread(runnable, threadPrefix + "-" + seq.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }
}
