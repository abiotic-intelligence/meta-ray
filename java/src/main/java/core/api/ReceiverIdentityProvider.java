package core.api;

public interface ReceiverIdentityProvider {
  ReceiverIdentity currentIdentity();

  default String currentReceiverId() {
    return currentIdentity().id;
  }

  default String currentKeyId() {
    return currentIdentity().keyId;
  }

  default String currentPublicKey() {
    return currentIdentity().publicKey;
  }

  default String currentPrivateKeyRef() {
    return currentIdentity().privateKeyRef;
  }

  byte[] signAuthPayload(byte[] payload);

  void reset();
}
