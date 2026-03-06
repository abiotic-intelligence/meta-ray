package core.api;

import core.util.Checks;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

final class ReceiverIdentityCrypto {
  static final String HASH_PREFIX = "sha256:";
  static final String BASE64URL_PREFIX = "base64url:";

  private ReceiverIdentityCrypto() {}

  static KeyMaterial generate(String privateKeyRef) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
      KeyPair pair = generator.generateKeyPair();
      byte[] publicKeyBytes = pair.getPublic().getEncoded();
      byte[] privateKeyBytes = pair.getPrivate().getEncoded();
      ReceiverIdentity identity = new ReceiverIdentity(
          UUID.randomUUID().toString(),
          toHexKeyId(publicKeyBytes),
          encodePublicKey(publicKeyBytes),
          Checks.notBlank(privateKeyRef, "privateKeyRef")
      );
      return new KeyMaterial(identity, pair.getPublic(), pair.getPrivate(), encodeBase64Url(privateKeyBytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Unable to generate Ed25519 keypair", e);
    }
  }

  static KeyMaterial fromStored(
      String id,
      String keyId,
      String encodedPublicKey,
      String encodedPrivateKeyPkcs8,
      String privateKeyRef
  ) {
    try {
      byte[] publicKeyBytes = decodeBase64Url(encodedPublicKey);
      byte[] privateKeyBytes = decodeBase64Url(encodedPrivateKeyPkcs8);
      validateKeyId(keyId, publicKeyBytes);

      KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
      PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
      PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
      ReceiverIdentity identity = new ReceiverIdentity(id, keyId, normalizeBase64Url(encodedPublicKey), privateKeyRef);
      return new KeyMaterial(identity, publicKey, privateKey, encodeBase64Url(privateKeyBytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("Stored receiver identity is invalid", e);
    }
  }

  static byte[] sign(PrivateKey privateKey, byte[] payload) {
    Checks.notNull(privateKey, "privateKey");
    Checks.notNull(payload, "payload");
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(payload);
      return signer.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Unable to sign auth payload", e);
    }
  }

  static PublicKey decodePublicKey(String encodedPublicKey) {
    try {
      byte[] publicKeyBytes = decodeBase64Url(encodedPublicKey);
      KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
      return keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("publicKey is invalid", e);
    }
  }

  private static void validateKeyId(String keyId, byte[] publicKeyBytes) {
    String id = Checks.notBlank(keyId, "keyId");
    byte[] hash = sha256(publicKeyBytes);
    if (!id.regionMatches(true, 0, HASH_PREFIX, 0, HASH_PREFIX.length())) {
      throw new IllegalArgumentException("keyId must start with sha256:");
    }
    String raw = id.substring(HASH_PREFIX.length());
    String hashHex = HexFormat.of().formatHex(hash);
    String hashB64 = encodeBase64Url(hash);
    if (!raw.equalsIgnoreCase(hashHex) && !raw.equals(hashB64)) {
      throw new IllegalArgumentException("keyId does not match public key");
    }
  }

  private static String toHexKeyId(byte[] publicKeyBytes) {
    return HASH_PREFIX + HexFormat.of().formatHex(sha256(publicKeyBytes));
  }

  private static String encodePublicKey(byte[] publicKeyBytes) {
    return BASE64URL_PREFIX + encodeBase64Url(publicKeyBytes);
  }

  private static String normalizeBase64Url(String value) {
    return BASE64URL_PREFIX + decodeAndEncodeBase64Url(value);
  }

  private static String decodeAndEncodeBase64Url(String value) {
    return encodeBase64Url(decodeBase64Url(value));
  }

  private static String encodeBase64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] decodeBase64Url(String value) {
    String raw = Checks.notBlank(value, "value");
    if (raw.startsWith(BASE64URL_PREFIX)) {
      raw = raw.substring(BASE64URL_PREFIX.length());
    }
    return Base64.getUrlDecoder().decode(raw);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      return d.digest(bytes);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  static final class KeyMaterial {
    final ReceiverIdentity identity;
    final PublicKey publicKey;
    final PrivateKey privateKey;
    final String privateKeyPkcs8;

    KeyMaterial(ReceiverIdentity identity, PublicKey publicKey, PrivateKey privateKey, String privateKeyPkcs8) {
      this.identity = Checks.notNull(identity, "identity");
      this.publicKey = Checks.notNull(publicKey, "publicKey");
      this.privateKey = Checks.notNull(privateKey, "privateKey");
      this.privateKeyPkcs8 = Checks.notBlank(privateKeyPkcs8, "privateKeyPkcs8");
    }
  }
}
