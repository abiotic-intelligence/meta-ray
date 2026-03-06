package testkit;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public final class VirtualNetworkHarness {
  private final Random random;
  private final double lossRate;
  private final boolean reorder;
  private final List<DeliveredPacket> buffer = new ArrayList<>();
  private long nextSequence;

  public VirtualNetworkHarness(double lossRate, boolean reorder) {
    this(lossRate, reorder, new SecureRandom().nextLong());
  }

  public VirtualNetworkHarness(double lossRate, boolean reorder, long seed) {
    if (lossRate < 0.0 || lossRate > 1.0) throw new IllegalArgumentException("lossRate must be in [0..1]");
    this.random = new Random(seed);
    this.lossRate = lossRate;
    this.reorder = reorder;
  }

  public void send(byte[] packet) {
    send(nextSequence++, packet);
  }

  public void send(long sequence, byte[] packet) {
    if (sequence < 0L) throw new IllegalArgumentException("sequence must be >= 0");
    if (packet == null) throw new IllegalArgumentException("packet must not be null");
    if (random.nextDouble() < lossRate) return;
    buffer.add(new DeliveredPacket(sequence, packet));
  }

  public List<DeliveredPacket> drainDeliveredPackets() {
    List<DeliveredPacket> out = new ArrayList<>(buffer);
    buffer.clear();
    if (reorder && out.size() > 1) {
      List<DeliveredPacket> original = new ArrayList<>(out);
      Collections.shuffle(out, random);
      if (sameOrder(original, out)) {
        Collections.swap(out, 0, 1);
      }
    }
    return out;
  }

  public List<byte[]> drainDelivered() {
    List<DeliveredPacket> packets = drainDeliveredPackets();
    List<byte[]> out = new ArrayList<>(packets.size());
    for (DeliveredPacket packet : packets) {
      out.add(packet.payload());
    }
    return out;
  }

  private static boolean sameOrder(List<DeliveredPacket> a, List<DeliveredPacket> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (a.get(i).sequence() != b.get(i).sequence()) {
        return false;
      }
    }
    return true;
  }

  public static final class DeliveredPacket {
    private final long sequence;
    private final byte[] payload;

    public DeliveredPacket(long sequence, byte[] payload) {
      this.sequence = sequence;
      this.payload = Objects.requireNonNull(payload, "payload").clone();
    }

    public long sequence() {
      return sequence;
    }

    public byte[] payload() {
      return payload.clone();
    }
  }
}
