package core.spi;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface ControlTransport {
  CompletableFuture<byte[]> request(byte[] payload, Duration timeout);
  void close();
}
