package core.spi;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

public interface MulticastTransport {
  void join(InetSocketAddress group);
  void leave(InetSocketAddress group);

  CompletableFuture<Void> send(byte[] payload);
  void onPacket(PacketHandler<byte[]> handler);
  void close();
}
