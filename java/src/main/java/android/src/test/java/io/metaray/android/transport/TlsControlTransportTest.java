package io.metaray.android.transport;

import static org.junit.Assert.assertArrayEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.Test;

public final class TlsControlTransportTest {
  @Test
  public void requestRoundTripOverTlsWithGeneratedTestMaterial() throws Exception {
    int port = freePort();
    byte[] requestPayload = "ping".getBytes(StandardCharsets.UTF_8);
    byte[] responsePayload = "pong".getBytes(StandardCharsets.UTF_8);

    TlsControlServer server = new TlsControlServer(
        port,
        TestTlsMaterial.serverSocketFactory(),
        Executors.newSingleThreadExecutor()
    );
    server.start(payload -> {
      assertArrayEquals(requestPayload, payload);
      return responsePayload;
    });

    TlsControlTransport transport = new TlsControlTransport(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        Duration.ofSeconds(3),
        TestTlsMaterial.clientSocketFactory(),
        Runnable::run
    );
    try {
      byte[] actual = transport.request(requestPayload, Duration.ofSeconds(3)).get();
      assertArrayEquals(responsePayload, actual);
    } finally {
      transport.close();
      server.close();
    }
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
