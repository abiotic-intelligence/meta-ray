package io.metaray.jvm.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TcpControlTransportTest {
  @Test
  void requestRoundTrip() throws Exception {
    byte[] requestPayload = "ping".getBytes(StandardCharsets.UTF_8);
    byte[] responsePayload = "pong".getBytes(StandardCharsets.UTF_8);

    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<?> serverTask = serverExecutor.submit(() -> {
        try (Socket client = server.accept();
             DataInputStream in = new DataInputStream(client.getInputStream());
             DataOutputStream out = new DataOutputStream(client.getOutputStream())) {
          int len = in.readInt();
          byte[] payload = in.readNBytes(len);
          assertArrayEquals(requestPayload, payload);

          out.writeInt(responsePayload.length);
          out.write(responsePayload);
          out.flush();
          return null;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });

      InetSocketAddress endpoint = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
      TcpControlTransport transport = new TcpControlTransport(endpoint);
      try {
        byte[] actual = transport.request(requestPayload, Duration.ofSeconds(2)).get();
        assertArrayEquals(responsePayload, actual);
      } finally {
        transport.close();
      }

      try {
        serverTask.get();
      } finally {
        serverExecutor.shutdownNow();
      }
    }
  }
}
