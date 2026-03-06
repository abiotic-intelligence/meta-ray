package io.metaray.android.transport;

import core.spi.ControlTransport;
import io.metaray.android.config.AndroidTransportConfig;
import io.metaray.android.internal.AndroidExecutors;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Android TLS control transport with length-prefixed request/response framing.
 *
 * <p>Threading model: each request runs asynchronously on a background I/O executor.</p>
 */
public final class TlsControlTransport implements ControlTransport {
  private final InetSocketAddress endpoint;
  private final Duration connectTimeout;
  private final SSLSocketFactory socketFactory;
  private final Executor ioExecutor;
  private final ExecutorService ownedExecutor;
  private final AtomicBoolean closed;

  public TlsControlTransport(InetSocketAddress endpoint) {
    this(
        endpoint,
        Duration.ofSeconds(3),
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        AndroidExecutors.newCachedIoExecutor("metaray-android-tls-control-io"),
        true
    );
  }

  public TlsControlTransport(InetSocketAddress endpoint, AndroidTransportConfig config) {
    this(
        endpoint,
        Objects.requireNonNull(config, "config must not be null").connectTimeout,
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        AndroidExecutors.newCachedIoExecutor("metaray-android-tls-control-io"),
        true
    );
  }

  public TlsControlTransport(
      InetSocketAddress endpoint,
      Duration connectTimeout,
      SSLSocketFactory socketFactory,
      Executor ioExecutor
  ) {
    this(endpoint, connectTimeout, socketFactory, ioExecutor, false);
  }

  private TlsControlTransport(
      InetSocketAddress endpoint,
      Duration connectTimeout,
      SSLSocketFactory socketFactory,
      Executor ioExecutor,
      boolean ownsExecutor
  ) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
    this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
    this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory must not be null");
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
    try (SSLSocket socket = (SSLSocket) socketFactory.createSocket()) {
      socket.connect(endpoint, toTimeoutMs(connectTimeout));
      socket.setSoTimeout(toTimeoutMs(timeout));
      socket.startHandshake();

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
        throw new IOException("Remote closed TLS connection before full response", e);
      }
    } catch (IOException e) {
      throw new IllegalStateException("TLS control request failed", e);
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
