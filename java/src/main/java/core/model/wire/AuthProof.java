package core.model.wire;

import core.util.Checks;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public final class AuthProof {
  public static final String PROTOCOL_PREFIX = "metaray-auth-v1";
  public static final String BASE64URL_PREFIX = "base64url:";
  public static final String SHA256_PREFIX = "sha256:";

  private AuthProof() {}

  public static byte[] canonicalPayload(String emitterId, String receiverKeyId, String nonce) {
    String payload = PROTOCOL_PREFIX
        + Checks.notBlank(emitterId, "emitterId")
        + Checks.notBlank(receiverKeyId, "receiverKeyId")
        + Checks.notBlank(nonce, "nonce");
    return payload.getBytes(StandardCharsets.UTF_8);
  }

  public static String encodeBase64Url(byte[] bytes) {
    return BASE64URL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(Checks.notNull(bytes, "bytes"));
  }

  public static byte[] decodeBase64Url(String value) {
    String raw = Checks.notBlank(value, "value");
    if (raw.startsWith(BASE64URL_PREFIX)) {
      raw = raw.substring(BASE64URL_PREFIX.length());
    }
    return Base64.getUrlDecoder().decode(raw);
  }

  public static void requireKeyIdMatchesPublicKey(String keyId, String encodedPublicKey) {
    String normalizedKeyId = Checks.notBlank(keyId, "keyId");
    byte[] publicKeyBytes = decodeBase64Url(encodedPublicKey);
    byte[] hash = sha256(publicKeyBytes);
    if (!normalizedKeyId.regionMatches(true, 0, SHA256_PREFIX, 0, SHA256_PREFIX.length())) {
      throw new IllegalArgumentException("keyId must start with sha256:");
    }
    String raw = normalizedKeyId.substring(SHA256_PREFIX.length());
    String hashHex = HexFormat.of().formatHex(hash);
    String hashB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    if (!raw.equalsIgnoreCase(hashHex) && !raw.equals(hashB64)) {
      throw new IllegalArgumentException("keyId does not match public key");
    }
  }

  public static boolean verifyEd25519(String encodedPublicKey, byte[] payload, String encodedSignature) {
    try {
      PublicKey publicKey = decodeEd25519PublicKey(encodedPublicKey);
      byte[] signatureBytes = decodeBase64Url(encodedSignature);
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(Checks.notNull(payload, "payload"));
      return verifier.verify(signatureBytes);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  private static PublicKey decodeEd25519PublicKey(String encodedPublicKey) throws GeneralSecurityException {
    byte[] publicKeyBytes = decodeBase64Url(encodedPublicKey);
    KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
    return keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return digest.digest(bytes);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
