package core.api;

import core.util.Checks;

public final class ReceiverIdentity {
  public final String id;
  public final String keyId;
  public final String publicKey;
  public final String privateKeyRef;

  public ReceiverIdentity(String id, String keyId, String publicKey, String privateKeyRef) {
    this.id = Checks.notBlank(id, "id");
    this.keyId = Checks.notBlank(keyId, "keyId");
    this.publicKey = Checks.notBlank(publicKey, "publicKey");
    this.privateKeyRef = Checks.notBlank(privateKeyRef, "privateKeyRef");
  }
}
