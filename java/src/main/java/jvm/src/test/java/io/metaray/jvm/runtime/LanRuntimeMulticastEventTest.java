package io.metaray.jvm.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import core.api.EphemeralReceiverIdentityProvider;
import core.model.ContextFrame;
import core.model.EmitterConfig;
import core.model.PullOptions;
import core.model.Session;
import core.spi.MulticastTransport;
import core.spi.PacketHandler;
import io.metaray.jvm.codec.JacksonCborCodec;
import io.metaray.jvm.codec.JacksonJsonCodec;
import io.metaray.jvm.discovery.ManualDiscovery;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LanRuntimeMulticastEventTest {
  @Test
  void multicastEventsAreDeliveredAfterConnect() throws Exception {
    int controlPort = freeTcpPort();
    EmitterConfig info = new EmitterConfig(
        1,
        "emitter-push",
        "127.0.0.1",
        controlPort,
        "239.1.2.3",
        5001,
        Set.of("json", "cbor"),
        Set.of("tls"),
        Set.of("assets", "actions")
    );

    FakeMulticastTransport emitterMulticast = new FakeMulticastTransport();
    FakeMulticastTransport receiverMulticast = new FakeMulticastTransport();
    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        info,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        null,
        TestTlsMaterial.serverSocketFactory(),
        emitterMulticast
    );
    EphemeralReceiverIdentityProvider identity = new EphemeralReceiverIdentityProvider();
    emitter.registerReceiver(identity.currentKeyId(), identity.currentReceiverId(), identity.currentPublicKey());
    LanMetaRayReceiver receiver = new LanMetaRayReceiver(
        null,
        new ManualDiscovery(List.of(info)),
        new JacksonJsonCodec(),
        target -> TestTlsMaterial.newTransport(new InetSocketAddress(target.host, target.controlPort)),
        ignored -> {
          receiverMulticast.join(new InetSocketAddress(info.multicastGroup, info.multicastPort));
          return receiverMulticast;
        },
        identity
    );
    try {
      emitter.startAdvertising();
      emitter.startControlServer();

      Session session = receiver.connect(info);
      assertNotNull(session);
      assertEquals("cbor", session.codec);

      emitter.pushEvent(new ContextFrame(
          new ContextFrame.Std("events", true, "evt-1"),
          Map.of("contentId", "c1", "appStateId", "s1", "mediaTimeMs", 10L)
      ));
      List<ContextFrame> firstPull = awaitPullCount(receiver, "events", 1);
      assertEquals("evt-1", firstPull.get(0).std.eventId);

      emitter.pushEvent(new ContextFrame(
          new ContextFrame.Std("events", true, "evt-2"),
          Map.of("contentId", "c1", "appStateId", "s1", "mediaTimeMs", 11L)
      ));
      List<ContextFrame> secondPull = awaitPullCount(receiver, "events", 2);
      assertEquals("evt-2", secondPull.get(1).std.eventId);
    } finally {
      receiver.close();
      emitter.shutdown();
    }
  }

  private static List<ContextFrame> awaitPullCount(
      LanMetaRayReceiver receiver,
      String contextId,
      int minCount
  ) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() < deadline) {
      List<ContextFrame> frames = receiver.pullContext(contextId, PullOptions.all());
      if (frames.size() >= minCount) {
        return frames;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("Timed out waiting for pulled context frames");
  }

  private static int freeTcpPort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static final class FakeMulticastTransport implements MulticastTransport {
    private static final ConcurrentHashMap<InetSocketAddress, CopyOnWriteArrayList<FakeMulticastTransport>> GROUPS =
        new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile InetSocketAddress group;
    private volatile PacketHandler<byte[]> handler;

    @Override
    public void join(InetSocketAddress group) {
      if (closed.get()) throw new IllegalStateException("transport is closed");
      this.group = group;
      GROUPS.computeIfAbsent(group, ignored -> new CopyOnWriteArrayList<>()).add(this);
    }

    @Override
    public void leave(InetSocketAddress group) {
      CopyOnWriteArrayList<FakeMulticastTransport> members = GROUPS.get(group);
      if (members != null) {
        members.remove(this);
      }
      if (group.equals(this.group)) {
        this.group = null;
      }
    }

    @Override
    public CompletableFuture<Void> send(byte[] payload) {
      if (closed.get()) {
        return CompletableFuture.failedFuture(new IllegalStateException("transport is closed"));
      }
      InetSocketAddress current = group;
      if (current == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("No joined multicast group"));
      }
      CopyOnWriteArrayList<FakeMulticastTransport> members = GROUPS.get(current);
      if (members != null) {
        for (FakeMulticastTransport member : members) {
          PacketHandler<byte[]> packetHandler = member.handler;
          if (packetHandler != null && !member.closed.get()) {
            packetHandler.onPacket(payload.clone());
          }
        }
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onPacket(PacketHandler<byte[]> handler) {
      this.handler = handler;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        InetSocketAddress current = group;
        if (current != null) {
          leave(current);
        }
      }
    }
  }
}
