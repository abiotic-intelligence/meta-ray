package core.api;

import java.util.concurrent.atomic.AtomicReference;

public final class EphemeralReceiverIdentityProvider implements ReceiverIdentityProvider {
  private static final String PRIVATE_KEY_REF = "memory://metaray/receiverKey";

  private final AtomicReference<ReceiverIdentityCrypto.KeyMaterial> state =
      new AtomicReference<>(ReceiverIdentityCrypto.generate(PRIVATE_KEY_REF));

  @Override
  public ReceiverIdentity currentIdentity() {
    return state.get().identity;
  }

  @Override
  public byte[] signAuthPayload(byte[] payload) {
    return ReceiverIdentityCrypto.sign(state.get().privateKey, payload);
  }

  @Override
  public void reset() {
    state.set(ReceiverIdentityCrypto.generate(PRIVATE_KEY_REF));
  }
}
