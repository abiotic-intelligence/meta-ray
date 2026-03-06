package testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VirtualNetworkHarnessTest {
  @Test
  void deliversPacketsWhenLossDisabled() {
    VirtualNetworkHarness harness = new VirtualNetworkHarness(0.0, false);
    harness.send(new byte[] {1});
    harness.send(new byte[] {2});
    List<byte[]> out = harness.drainDelivered();
    assertEquals(2, out.size());
    assertEquals(1, out.get(0)[0]);
    assertEquals(2, out.get(1)[0]);
  }

  @Test
  void dropsAllPacketsWhenLossOne() {
    VirtualNetworkHarness harness = new VirtualNetworkHarness(1.0, true);
    harness.send(new byte[] {1});
    harness.send(new byte[] {2});
    assertTrue(harness.drainDelivered().isEmpty());
  }

  @Test
  void lossyAndReorderedScenarioProducesDroppedOutOfOrderDelivery() {
    VirtualNetworkHarness harness = new VirtualNetworkHarness(0.2, true, 11L);
    for (int i = 0; i < 50; i++) {
      harness.send(new byte[] {(byte) i});
    }

    List<VirtualNetworkHarness.DeliveredPacket> delivered = harness.drainDeliveredPackets();
    assertTrue(delivered.size() > 1, "Expected at least two packets to validate reordering");
    assertTrue(delivered.size() < 50, "Expected packet loss in lossy scenario");

    boolean outOfOrder = false;
    for (int i = 1; i < delivered.size(); i++) {
      if (delivered.get(i).sequence() < delivered.get(i - 1).sequence()) {
        outOfOrder = true;
        break;
      }
    }
    assertTrue(outOfOrder, "Expected at least one out-of-order sequence transition");
  }
}
