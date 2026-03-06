package io.metaray.android.transport;

import core.spi.MulticastTransport;
import core.spi.PacketHandler;
import io.metaray.android.config.AndroidTransportConfig;
import io.metaray.android.internal.AndroidExecutors;
import io.metaray.android.net.WifiMulticastLockManager;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Android UDP multicast transport.
 *
 * <p>Threading model: inbound packets are delivered sequentially on a single dedicated receiver
 * thread. Outbound sends run asynchronously on the configured I/O executor.</p>
 */
public final class UdpMulticastTransport implements MulticastTransport {
  private static final int MAX_PACKET_SIZE = 65_507;

  private final MulticastSocket socket;
  private final Executor ioExecutor;
  private final ExecutorService ownedExecutor;
  private final AtomicBoolean closed;
  private final AtomicReference<PacketHandler<byte[]>> packetHandler;
  private final Thread receiverThread;
  private final WifiMulticastLockManager multicastLockManager;
  private volatile InetSocketAddress currentGroup;

  public UdpMulticastTransport(int localPort) {
    this(
        newSocket(localPort),
        null,
        AndroidExecutors.newCachedIoExecutor("metaray-android-multicast-io"),
        true
    );
  }

  public UdpMulticastTransport(AndroidTransportConfig config, WifiMulticastLockManager lockManager) {
    this(
        newSocket(Objects.requireNonNull(config, "config must not be null").multicastLocalPort),
        lockManager,
        AndroidExecutors.newCachedIoExecutor("metaray-android-multicast-io"),
        true
    );
  }

  public UdpMulticastTransport(
      MulticastSocket socket,
      WifiMulticastLockManager lockManager,
      Executor ioExecutor
  ) {
    this(socket, lockManager, ioExecutor, false);
  }

  private UdpMulticastTransport(
      MulticastSocket socket,
      WifiMulticastLockManager lockManager,
      Executor ioExecutor,
      boolean ownsExecutor
  ) {
    this.socket = Objects.requireNonNull(socket, "socket must not be null");
    this.multicastLockManager = lockManager;
    this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor must not be null");
    this.ownedExecutor = ownsExecutor && ioExecutor instanceof ExecutorService ? (ExecutorService) ioExecutor : null;
    this.closed = new AtomicBoolean(false);
    this.packetHandler = new AtomicReference<>();
    this.receiverThread = new Thread(this::receiveLoop, "metaray-android-multicast-recv");
    this.receiverThread.setDaemon(true);
    this.receiverThread.start();
  }

  @Override
  public synchronized void join(InetSocketAddress group) {
    ensureOpen();
    Objects.requireNonNull(group, "group must not be null");
    InetAddress addr = group.getAddress();
    if (addr == null || !addr.isMulticastAddress()) {
      throw new IllegalArgumentException("group must be a multicast address");
    }
    if (currentGroup != null && currentGroup.equals(group)) {
      return;
    }
    try {
      if (multicastLockManager != null) {
        multicastLockManager.acquire();
      }
      if (currentGroup != null) {
        socket.leaveGroup(currentGroup.getAddress());
      }
      socket.joinGroup(addr);
      currentGroup = group;
    } catch (IOException e) {
      if (multicastLockManager != null) {
        multicastLockManager.release();
      }
      throw new IllegalStateException("Failed to join multicast group", e);
    }
  }

  @Override
  public synchronized void leave(InetSocketAddress group) {
    ensureOpen();
    Objects.requireNonNull(group, "group must not be null");
    try {
      if (currentGroup != null && currentGroup.equals(group)) {
        socket.leaveGroup(group.getAddress());
        currentGroup = null;
        if (multicastLockManager != null) {
          multicastLockManager.release();
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to leave multicast group", e);
    }
  }

  @Override
  public CompletableFuture<Void> send(byte[] payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    if (payload.length > MAX_PACKET_SIZE) {
      throw new IllegalArgumentException("payload exceeds UDP datagram max size");
    }
    ensureOpen();
    InetSocketAddress group = currentGroup;
    if (group == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("No joined multicast group"));
    }
    return CompletableFuture.runAsync(() -> {
      DatagramPacket packet = new DatagramPacket(payload, payload.length, group);
      try {
        socket.send(packet);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to send multicast packet", e);
      }
    }, ioExecutor);
  }

  @Override
  public void onPacket(PacketHandler<byte[]> handler) {
    packetHandler.set(Objects.requireNonNull(handler, "handler must not be null"));
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      socket.close();
      if (multicastLockManager != null) {
        multicastLockManager.releaseAll();
      }
      if (ownedExecutor != null) {
        ownedExecutor.shutdownNow();
      }
    }
  }

  private void receiveLoop() {
    while (!closed.get()) {
      byte[] buf = new byte[MAX_PACKET_SIZE];
      DatagramPacket packet = new DatagramPacket(buf, buf.length);
      try {
        socket.receive(packet);
        PacketHandler<byte[]> handler = packetHandler.get();
        if (handler != null) {
          byte[] payload = new byte[packet.getLength()];
          System.arraycopy(packet.getData(), packet.getOffset(), payload, 0, packet.getLength());
          handler.onPacket(payload);
        }
      } catch (SocketException e) {
        if (!closed.get()) {
          throw new IllegalStateException("Multicast receive loop failed", e);
        }
      } catch (IOException e) {
        if (!closed.get()) {
          throw new IllegalStateException("Multicast receive loop failed", e);
        }
      }
    }
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("transport is closed");
    }
  }

  private static MulticastSocket newSocket(int localPort) {
    try {
      return new MulticastSocket(localPort);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to bind multicast socket on port " + localPort, e);
    }
  }
}
