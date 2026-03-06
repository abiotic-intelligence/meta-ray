package io.metaray.android.crypto;

import core.spi.CryptoProvider;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmCryptoProvider implements CryptoProvider {
  private static final String ALGO = "AES/GCM/NoPadding";
  private static final String KEY_ALGO = "AES";
  private static final int IV_SIZE = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random;

  public AesGcmCryptoProvider(SecretKey key) {
    this(key, new SecureRandom());
  }

  public AesGcmCryptoProvider(SecretKey key, SecureRandom random) {
    this.key = Objects.requireNonNull(key, "key must not be null");
    this.random = Objects.requireNonNull(random, "random must not be null");
  }

  public AesGcmCryptoProvider(byte[] rawKey) {
    this(rawKey, new SecureRandom());
  }

  public AesGcmCryptoProvider(byte[] rawKey, SecureRandom random) {
    this(new SecretKeySpec(validateRawKey(rawKey), KEY_ALGO), random);
  }

  @Override
  public byte[] seal(byte[] plaintext) {
    Objects.requireNonNull(plaintext, "plaintext must not be null");
    byte[] iv = new byte[IV_SIZE];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(ALGO);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(IV_SIZE + encrypted.length).put(iv).put(encrypted).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt payload", e);
    }
  }

  @Override
  public byte[] open(byte[] ciphertext) {
    Objects.requireNonNull(ciphertext, "ciphertext must not be null");
    if (ciphertext.length <= IV_SIZE) {
      throw new IllegalArgumentException("ciphertext must include IV and auth tag");
    }
    byte[] iv = new byte[IV_SIZE];
    byte[] encrypted = new byte[ciphertext.length - IV_SIZE];
    System.arraycopy(ciphertext, 0, iv, 0, IV_SIZE);
    System.arraycopy(ciphertext, IV_SIZE, encrypted, 0, encrypted.length);
    try {
      Cipher cipher = Cipher.getInstance(ALGO);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return cipher.doFinal(encrypted);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to decrypt payload", e);
    }
  }

  private static byte[] validateRawKey(byte[] rawKey) {
    Objects.requireNonNull(rawKey, "rawKey must not be null");
    if (rawKey.length != 16 && rawKey.length != 24 && rawKey.length != 32) {
      throw new IllegalArgumentException("rawKey must be 16, 24, or 32 bytes for AES");
    }
    return rawKey.clone();
  }
}
