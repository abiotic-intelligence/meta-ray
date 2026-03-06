package core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class InstallScopedReceiverIdentityProviderTest {
  @Test
  void bootstrapsAndPersistsIdentityPerAppInstall() {
    Preferences node = testNode();
    try {
      InstallScopedReceiverIdentityProvider first = new InstallScopedReceiverIdentityProvider("netflix", node);
      ReceiverIdentity firstIdentity = first.currentIdentity();
      assertTrue(firstIdentity.id.matches("^[0-9a-fA-F\\-]{36}$"));
      assertTrue(firstIdentity.keyId.startsWith("sha256:"));
      assertTrue(firstIdentity.publicKey.startsWith("base64url:"));
      assertTrue(firstIdentity.privateKeyRef.startsWith("prefs://"));

      byte[] payload = "metaray-auth-test".getBytes(StandardCharsets.UTF_8);
      byte[] signature = first.signAuthPayload(payload);
      assertTrue(verify(firstIdentity.publicKey, payload, signature));

      InstallScopedReceiverIdentityProvider second = new InstallScopedReceiverIdentityProvider("netflix", node);
      ReceiverIdentity secondIdentity = second.currentIdentity();
      assertEquals(firstIdentity.id, secondIdentity.id);
      assertEquals(firstIdentity.keyId, secondIdentity.keyId);
      assertEquals(firstIdentity.publicKey, secondIdentity.publicKey);
      assertEquals(firstIdentity.privateKeyRef, secondIdentity.privateKeyRef);
      assertEquals(first.currentReceiverId(), second.currentReceiverId());
      assertEquals(first.currentKeyId(), second.currentKeyId());
      assertEquals(first.currentPublicKey(), second.currentPublicKey());
    } finally {
      deleteNode(node);
    }
  }

  @Test
  void resetRegeneratesIdentityAndPersistsIt() {
    Preferences node = testNode();
    try {
      InstallScopedReceiverIdentityProvider provider = new InstallScopedReceiverIdentityProvider("netflix", node);
      ReceiverIdentity beforeReset = provider.currentIdentity();

      provider.reset();
      ReceiverIdentity afterReset = provider.currentIdentity();
      assertNotEquals(beforeReset.id, afterReset.id);
      assertNotEquals(beforeReset.keyId, afterReset.keyId);
      assertNotEquals(beforeReset.publicKey, afterReset.publicKey);

      InstallScopedReceiverIdentityProvider reloaded = new InstallScopedReceiverIdentityProvider("netflix", node);
      ReceiverIdentity reloadedIdentity = reloaded.currentIdentity();
      assertEquals(afterReset.id, reloadedIdentity.id);
      assertEquals(afterReset.keyId, reloadedIdentity.keyId);
      assertEquals(afterReset.publicKey, reloadedIdentity.publicKey);
      assertEquals(afterReset.privateKeyRef, reloadedIdentity.privateKeyRef);
    } finally {
      deleteNode(node);
    }
  }

  private static boolean verify(String encodedPublicKey, byte[] payload, byte[] signatureBytes) {
    try {
      PublicKey publicKey = ReceiverIdentityCrypto.decodePublicKey(encodedPublicKey);
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(payload);
      return verifier.verify(signatureBytes);
    } catch (Exception e) {
      return false;
    }
  }

  private static Preferences testNode() {
    return Preferences.userRoot().node("/io/metaray/tests/receiverIdentity/" + UUID.randomUUID());
  }

  private static void deleteNode(Preferences node) {
    try {
      node.removeNode();
      node.flush();
    } catch (BackingStoreException ignored) {
      // Best effort cleanup.
    }
  }
}
