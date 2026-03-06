package io.metaray.jvm.transport;

import core.spi.ControlTransport;
import io.metaray.jvm.config.JvmTransportConfig;
import io.metaray.jvm.internal.JvmExecutors;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM TCP control transport with length-prefixed request/response frames.
 *
 * <p>Threading model: each request executes asynchronously on the configured I/O executor.</p>
 */
public final class TcpControlTransport implements ControlTransport {
  private final InetSocketAddress endpoint;
  private final Duration connectTimeout;
  private final Executor ioExecutor;
  private final ExecutorService ownedExecutor;
  private final AtomicBoolean closed;

  public TcpControlTransport(InetSocketAddress endpoint) {
    this(
        endpoint,
        Duration.ofSeconds(3),
        JvmExecutors.newCachedIoExecutor("metaray-jvm-control-io"),
        true
    );
  }

  public TcpControlTransport(InetSocketAddress endpoint, JvmTransportConfig config) {
    this(
        endpoint,
        Objects.requireNonNull(config, "config must not be null").connectTimeout,
        JvmExecutors.newCachedIoExecutor("metaray-jvm-control-io"),
        true
    );
  }

  public TcpControlTransport(InetSocketAddress endpoint, Duration connectTimeout, Executor ioExecutor) {
    this(endpoint, connectTimeout, ioExecutor, false);
  }

  private TcpControlTransport(
      InetSocketAddress endpoint,
      Duration connectTimeout,
      Executor ioExecutor,
      boolean ownsExecutor
  ) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
    this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
    this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor must not be null");
    this.ownedExecutor = ownsExecutor && ioExecutor instanceof ExecutorService ? (ExecutorService) ioExecutor : null;
    this.closed = new AtomicBoolean(false);
  }

  @Override
  public CompletableFuture<byte[]> request(byte[] payload, Duration timeout) {
    Objects.requireNonNull(payload, "payload must not be null");
    Duration reqTimeout = requirePositive(timeout, "timeout");
    if (closed.get()) {
      return CompletableFuture.failedFuture(new IllegalStateException("transport is closed"));
    }
    return CompletableFuture.supplyAsync(() -> doRequest(payload, reqTimeout), ioExecutor);
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true) && ownedExecutor != null) {
      ownedExecutor.shutdownNow();
    }
  }

  private byte[] doRequest(byte[] payload, Duration timeout) {
    try (Socket socket = new Socket()) {
      socket.connect(endpoint, toTimeoutMs(connectTimeout));
      socket.setSoTimeout(toTimeoutMs(timeout));

      try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
           DataInputStream in = new DataInputStream(socket.getInputStream())) {
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();

        int responseLen = in.readInt();
        if (responseLen < 0) {
          throw new IOException("Negative response length");
        }
        byte[] response = new byte[responseLen];
        in.readFully(response);
        return response;
      } catch (EOFException e) {
        throw new IOException("Remote closed connection before full response", e);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Control request failed", e);
    }
  }

  private static Duration requirePositive(Duration d, String name) {
    Objects.requireNonNull(d, name + " must not be null");
    if (d.isZero() || d.isNegative()) {
      throw new IllegalArgumentException(name + " must be > 0");
    }
    return d;
  }

  private static int toTimeoutMs(Duration d) {
    long ms = d.toMillis();
    if (ms > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) ms;
  }
}
