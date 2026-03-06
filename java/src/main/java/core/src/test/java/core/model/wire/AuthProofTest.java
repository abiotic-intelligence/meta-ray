package core.model.wire;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.api.EphemeralReceiverIdentityProvider;
import core.api.ReceiverIdentity;
import org.junit.jupiter.api.Test;

class AuthProofTest {
  @Test
  void verifiesEd25519ProofOverCanonicalPayload() {
    EphemeralReceiverIdentityProvider provider = new EphemeralReceiverIdentityProvider();
    ReceiverIdentity identity = provider.currentIdentity();
    String nonce = "base64url:testNonce";
    byte[] payload = AuthProof.canonicalPayload("emitter-1", identity.keyId, nonce);
    String signature = AuthProof.encodeBase64Url(provider.signAuthPayload(payload));

    assertTrue(AuthProof.verifyEd25519(identity.publicKey, payload, signature));
    assertFalse(AuthProof.verifyEd25519(identity.publicKey, "different".getBytes(), signature));
  }

  @Test
  void validatesReceiverKeyIdAgainstPublicKey() {
    EphemeralReceiverIdentityProvider provider = new EphemeralReceiverIdentityProvider();
    ReceiverIdentity identity = provider.currentIdentity();
    assertDoesNotThrow(() -> AuthProof.requireKeyIdMatchesPublicKey(identity.keyId, identity.publicKey));
  }
}
