package core.api;

import core.util.Checks;
import core.util.MetaRayException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class InstallScopedReceiverIdentityProvider implements ReceiverIdentityProvider {
  private static final String KEY_ID = "id";
  private static final String KEY_KEY_ID = "key_id";
  private static final String KEY_PUBLIC_KEY = "public_key";
  private static final String KEY_PRIVATE_KEY_PKCS8 = "private_key_pkcs8";
  private static final String KEY_PRIVATE_KEY_REF = "private_key_ref";
  private static final int MAX_NODE_LEN = 64;

  private final Preferences store;
  private final String privateKeyRef;
  private final AtomicReference<ReceiverIdentityCrypto.KeyMaterial> current = new AtomicReference<>();

  public InstallScopedReceiverIdentityProvider(String appId) {
    this(appId, defaultStore(appId));
  }

  InstallScopedReceiverIdentityProvider(String appId, Preferences store) {
    Checks.notBlank(appId, "appId");
    this.store = Checks.notNull(store, "store");
    this.privateKeyRef = "prefs://io.metaray/" + sanitizeNodeName(appId) + "/receiverKey";
    current.set(loadOrBootstrap());
  }

  @Override
  public ReceiverIdentity currentIdentity() {
    return current.get().identity;
  }

  @Override
  public byte[] signAuthPayload(byte[] payload) {
    return ReceiverIdentityCrypto.sign(current.get().privateKey, payload);
  }

  @Override
  public synchronized void reset() {
    ReceiverIdentityCrypto.KeyMaterial generated = ReceiverIdentityCrypto.generate(privateKeyRef);
    persist(generated);
    current.set(generated);
  }

  private ReceiverIdentityCrypto.KeyMaterial loadOrBootstrap() {
    String id = store.get(KEY_ID, null);
    String keyId = store.get(KEY_KEY_ID, null);
    String publicKey = store.get(KEY_PUBLIC_KEY, null);
    String privateKeyPkcs8 = store.get(KEY_PRIVATE_KEY_PKCS8, null);
    String storedPrivateKeyRef = store.get(KEY_PRIVATE_KEY_REF, privateKeyRef);

    boolean missingField = id == null || keyId == null || publicKey == null || privateKeyPkcs8 == null;
    if (missingField) {
      ReceiverIdentityCrypto.KeyMaterial generated = ReceiverIdentityCrypto.generate(storedPrivateKeyRef);
      persist(generated);
      return generated;
    }

    try {
      return ReceiverIdentityCrypto.fromStored(id, keyId, publicKey, privateKeyPkcs8, storedPrivateKeyRef);
    } catch (RuntimeException invalid) {
      ReceiverIdentityCrypto.KeyMaterial generated = ReceiverIdentityCrypto.generate(privateKeyRef);
      persist(generated);
      return generated;
    }
  }

  private void persist(ReceiverIdentityCrypto.KeyMaterial keyMaterial) {
    ReceiverIdentity identity = keyMaterial.identity;
    store.put(KEY_ID, identity.id);
    store.put(KEY_KEY_ID, identity.keyId);
    store.put(KEY_PUBLIC_KEY, identity.publicKey);
    store.put(KEY_PRIVATE_KEY_PKCS8, keyMaterial.privateKeyPkcs8);
    store.put(KEY_PRIVATE_KEY_REF, identity.privateKeyRef);
    try {
      store.flush();
    } catch (BackingStoreException e) {
      throw new MetaRayException("Failed to persist receiver identity", e);
    }
  }

  private static Preferences defaultStore(String appId) {
    return Preferences.userRoot().node("/io/metaray/receiverIdentity/" + sanitizeNodeName(appId));
  }

  private static String sanitizeNodeName(String appId) {
    String sanitized = Checks.notBlank(appId, "appId")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9._-]", "_");
    if (sanitized.isBlank()) {
      return "app";
    }
    return sanitized.length() > MAX_NODE_LEN ? sanitized.substring(0, MAX_NODE_LEN) : sanitized;
  }
}
