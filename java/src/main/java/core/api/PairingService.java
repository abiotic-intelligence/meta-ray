package core.api;

import core.model.SessionKeys;

public interface PairingService {
  String startEmitterPairing();
  SessionKeys pairReceiver(String codeOrQrPayload);
}
