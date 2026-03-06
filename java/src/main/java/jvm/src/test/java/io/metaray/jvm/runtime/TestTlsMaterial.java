package io.metaray.jvm.runtime;

import io.metaray.jvm.transport.TlsControlTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

final class TestTlsMaterial {
  private static final String STORE_PASSWORD = "changeit";
  private static final char[] STORE_PASSWORD_CHARS = STORE_PASSWORD.toCharArray();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

  private static volatile Material cached;

  private TestTlsMaterial() {}

  static SSLServerSocketFactory serverSocketFactory() {
    return load().serverSocketFactory;
  }

  static SSLSocketFactory clientSocketFactory() {
    return load().clientSocketFactory;
  }

  static TlsControlTransport newTransport(InetSocketAddress endpoint) {
    return new TlsControlTransport(endpoint, CONNECT_TIMEOUT, clientSocketFactory(), Runnable::run);
  }

  private static Material load() {
    Material existing = cached;
    if (existing != null) {
      return existing;
    }
    synchronized (TestTlsMaterial.class) {
      if (cached == null) {
        cached = createMaterial();
      }
      return cached;
    }
  }

  private static Material createMaterial() {
    try {
      Path workDir = Files.createTempDirectory("metaray-test-tls-");
      Path serverKeystore = workDir.resolve("server-keystore.p12");
      Path truststore = workDir.resolve("truststore.p12");
      Path certPem = workDir.resolve("server-cert.pem");

      String keytool = resolveKeytool();
      run(
          keytool,
          List.of(
              "-genkeypair",
              "-alias", "metaray-test",
              "-keyalg", "RSA",
              "-keysize", "2048",
              "-validity", "3650",
              "-dname", "CN=localhost, OU=MetaRay, O=MetaRay, L=Test, ST=Test, C=US",
              "-ext", "SAN=dns:localhost,ip:127.0.0.1",
              "-storetype", "PKCS12",
              "-keystore", serverKeystore.toString(),
              "-storepass", STORE_PASSWORD,
              "-keypass", STORE_PASSWORD,
              "-noprompt"
          )
      );
      run(
          keytool,
          List.of(
              "-exportcert",
              "-alias", "metaray-test",
              "-keystore", serverKeystore.toString(),
              "-storepass", STORE_PASSWORD,
              "-rfc",
              "-file", certPem.toString()
          )
      );
      run(
          keytool,
          List.of(
              "-importcert",
              "-alias", "metaray-test",
              "-file", certPem.toString(),
              "-keystore", truststore.toString(),
              "-storetype", "PKCS12",
              "-storepass", STORE_PASSWORD,
              "-noprompt"
          )
      );

      KeyStore serverStore = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(serverKeystore)) {
        serverStore.load(in, STORE_PASSWORD_CHARS);
      }
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(serverStore, STORE_PASSWORD_CHARS);

      KeyStore trustStore = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(truststore)) {
        trustStore.load(in, STORE_PASSWORD_CHARS);
      }
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);

      SSLContext serverContext = SSLContext.getInstance("TLS");
      serverContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

      SSLContext clientContext = SSLContext.getInstance("TLS");
      clientContext.init(null, tmf.getTrustManagers(), new SecureRandom());

      return new Material(serverContext.getServerSocketFactory(), clientContext.getSocketFactory());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create test TLS material", e);
    }
  }

  private static void run(String executable, List<String> args) throws IOException, InterruptedException {
    List<String> command = new java.util.ArrayList<>();
    command.add(executable);
    command.addAll(args);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    byte[] output = readFully(process.getInputStream());
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException(
          "Command failed (" + exitCode + "): " + String.join(" ", command) + "\n"
              + new String(output, StandardCharsets.UTF_8)
      );
    }
  }

  private static byte[] readFully(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    in.transferTo(out);
    return out.toByteArray();
  }

  private static String resolveKeytool() {
    String javaHome = System.getProperty("java.home");
    if (javaHome != null && !javaHome.isBlank()) {
      Path candidate = Path.of(javaHome, "bin", isWindows() ? "keytool.exe" : "keytool");
      if (Files.isRegularFile(candidate)) {
        return candidate.toString();
      }
    }
    return "keytool";
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name");
    return os != null && os.toLowerCase().contains("win");
  }

  private static final class Material {
    final SSLServerSocketFactory serverSocketFactory;
    final SSLSocketFactory clientSocketFactory;

    Material(SSLServerSocketFactory serverSocketFactory, SSLSocketFactory clientSocketFactory) {
      this.serverSocketFactory = serverSocketFactory;
      this.clientSocketFactory = clientSocketFactory;
    }
  }
}
