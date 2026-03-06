package io.metaray.android.net;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class WifiMulticastLockManager {
  private final WifiManager.MulticastLock lock;
  private final AtomicInteger refs;

  public WifiMulticastLockManager(Context context, String tag) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(tag, "tag must not be null");
    WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    if (wifi == null) {
      throw new IllegalStateException("WifiManager is unavailable");
    }
    this.lock = wifi.createMulticastLock(tag);
    this.lock.setReferenceCounted(false);
    this.refs = new AtomicInteger(0);
  }

  public void acquire() {
    int next = refs.incrementAndGet();
    if (next == 1) {
      lock.acquire();
    }
  }

  public void release() {
    int current = refs.get();
    if (current <= 0) return;
    int next = refs.decrementAndGet();
    if (next == 0 && lock.isHeld()) {
      lock.release();
    }
  }

  public void releaseAll() {
    refs.set(0);
    if (lock.isHeld()) {
      lock.release();
    }
  }
}
