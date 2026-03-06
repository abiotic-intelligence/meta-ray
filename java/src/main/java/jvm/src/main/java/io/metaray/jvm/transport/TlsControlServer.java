package io.metaray.jvm.transport;

import io.metaray.jvm.internal.JvmExecutors;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

public final class TlsControlServer implements AutoCloseable {
  private final int port;
  private final ExecutorService ioExecutor;
  private final SSLServerSocketFactory socketFactory;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile SSLServerSocket serverSocket;
  private volatile Thread acceptThread;
  private volatile Function<byte[], byte[]> requestHandler;

  public TlsControlServer(int port) {
    this(
        port,
        (SSLServerSocketFactory) SSLServerSocketFactory.getDefault(),
        JvmExecutors.newCachedIoExecutor("metaray-jvm-tls-control-server-io")
    );
  }

  public TlsControlServer(int port, SSLServerSocketFactory socketFactory, ExecutorService ioExecutor) {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be in [1..65535]");
    this.port = port;
    this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory must not be null");
    this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor must not be null");
  }

  public synchronized void start(Function<byte[], byte[]> handler) {
    if (running.get()) return;
    this.requestHandler = Objects.requireNonNull(handler, "handler must not be null");
    try {
      this.serverSocket = (SSLServerSocket) socketFactory.createServerSocket(port);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to bind TLS control server on port " + port, e);
    }
    running.set(true);
    this.acceptThread = new Thread(this::acceptLoop, "metaray-jvm-tls-control-accept");
    this.acceptThread.setDaemon(true);
    this.acceptThread.start();
  }

  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) return;
    SSLServerSocket ss = serverSocket;
    serverSocket = null;
    if (ss != null) {
      try {
        ss.close();
      } catch (IOException ignored) {
      }
    }
  }

  @Override
  public void close() {
    stop();
    ioExecutor.shutdownNow();
  }

  private void acceptLoop() {
    while (running.get()) {
      try {
        SSLServerSocket ss = serverSocket;
        if (ss == null) return;
        Socket socket = ss.accept();
        ioExecutor.execute(() -> handleClient(socket));
      } catch (IOException e) {
        if (running.get()) {
          throw new IllegalStateException("TLS control accept loop failed", e);
        }
      }
    }
  }

  private void handleClient(Socket socket) {
    try (SSLSocket s = (SSLSocket) socket;
         DataInputStream in = new DataInputStream(s.getInputStream());
         DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
      s.startHandshake();
      while (running.get()) {
        int requestLen;
        try {
          requestLen = in.readInt();
        } catch (EOFException eof) {
          return;
        }
        if (requestLen < 0) throw new IOException("Negative request length");
        byte[] request = new byte[requestLen];
        in.readFully(request);

        byte[] response = requestHandler.apply(request);
        if (response == null) response = new byte[0];
        out.writeInt(response.length);
        out.write(response);
        out.flush();
      }
    } catch (IOException e) {
      if (running.get()) {
        throw new IllegalStateException("TLS control client handler failed", e);
      }
    }
  }
}
