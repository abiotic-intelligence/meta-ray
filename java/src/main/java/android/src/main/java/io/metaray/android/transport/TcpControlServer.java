package io.metaray.android.transport;

import io.metaray.android.internal.AndroidExecutors;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public final class TcpControlServer implements AutoCloseable {
  private final int port;
  private final ExecutorService ioExecutor;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile ServerSocket serverSocket;
  private volatile Function<byte[], byte[]> requestHandler;

  public TcpControlServer(int port) {
    this(port, AndroidExecutors.newCachedIoExecutor("metaray-android-control-server-io"));
  }

  public TcpControlServer(int port, ExecutorService ioExecutor) {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be in [1..65535]");
    this.port = port;
    this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor must not be null");
  }

  public synchronized void start(Function<byte[], byte[]> handler) {
    if (running.get()) return;
    this.requestHandler = Objects.requireNonNull(handler, "handler must not be null");
    try {
      this.serverSocket = new ServerSocket(port);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to bind TCP control server on port " + port, e);
    }
    running.set(true);
    Thread t = new Thread(this::acceptLoop, "metaray-android-control-accept");
    t.setDaemon(true);
    t.start();
  }

  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) return;
    ServerSocket ss = serverSocket;
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
        ServerSocket ss = serverSocket;
        if (ss == null) return;
        Socket socket = ss.accept();
        ioExecutor.execute(() -> handleClient(socket));
      } catch (IOException e) {
        if (running.get()) {
          throw new IllegalStateException("TCP control accept loop failed", e);
        }
      }
    }
  }

  private void handleClient(Socket socket) {
    try (Socket s = socket;
         DataInputStream in = new DataInputStream(s.getInputStream());
         DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
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
        throw new IllegalStateException("TCP control client handler failed", e);
      }
    }
  }
}
