package core.spi;

public interface CryptoProvider {
  byte[] seal(byte[] plaintext);
  byte[] open(byte[] ciphertext);
}
