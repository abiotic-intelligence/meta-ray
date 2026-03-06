package core.spi;

@FunctionalInterface
public interface PacketHandler<T> {
  void onPacket(T packet);
}