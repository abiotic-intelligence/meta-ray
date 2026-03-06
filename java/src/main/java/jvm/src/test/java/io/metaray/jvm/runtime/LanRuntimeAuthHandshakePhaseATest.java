package io.metaray.jvm.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.api.EphemeralReceiverIdentityProvider;
import core.api.ReceiverIdentity;
import core.model.EmitterConfig;
import core.model.PullOptions;
import core.model.wire.ErrorMessage;
import core.model.wire.AuthProof;
import core.model.wire.AuthMessage;
import core.model.wire.MsgType;
import core.model.wire.PullMessage;
import core.model.wire.WireMessage;
import io.metaray.jvm.codec.JacksonCborCodec;
import io.metaray.jvm.codec.JacksonJsonCodec;
import io.metaray.jvm.transport.TlsControlTransport;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LanRuntimeAuthHandshakePhaseATest {
  @Test
  void unknownReceiverGetsPairRequired() throws Exception {
    int port = freePort();
    EmitterConfig info = emitterConfig("emitter-auth-unknown", port);
    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        info,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        null,
        TestTlsMaterial.serverSocketFactory(),
        null
    );
    emitter.startAdvertising();
    emitter.startControlServer();

    EphemeralReceiverIdentityProvider receiverIdentity = new EphemeralReceiverIdentityProvider();
    TlsControlTransport transport = TestTlsMaterial.newTransport(new InetSocketAddress(info.host, info.controlPort));
    try {
      WireMessage<?> response = sendHello(transport, info.appId, receiverIdentity.currentIdentity());
      assertEquals(MsgType.AUTH, response.std.msgType);
      AuthMessage body = ControlWire.bodyAs(response, AuthMessage.class);
      assertEquals("pairRequired", body.step);
      assertEquals(info.deviceName, response.std.header.deviceName);
    } finally {
      transport.close();
      emitter.shutdown();
    }
  }

  @Test
  void knownReceiverGetsChallenge() throws Exception {
    int port = freePort();
    EmitterConfig info = emitterConfig("emitter-auth-known", port);
    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        info,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        null,
        TestTlsMaterial.serverSocketFactory(),
        null
    );
    emitter.startAdvertising();
    emitter.startControlServer();

    EphemeralReceiverIdentityProvider receiverIdentity = new EphemeralReceiverIdentityProvider();
    ReceiverIdentity identity = receiverIdentity.currentIdentity();
    emitter.registerReceiver(identity.keyId, identity.id, identity.publicKey);

    TlsControlTransport transport = TestTlsMaterial.newTransport(new InetSocketAddress(info.host, info.controlPort));
    try {
      WireMessage<?> response = sendHello(transport, info.appId, identity);
      assertEquals(MsgType.AUTH, response.std.msgType);
      AuthMessage body = ControlWire.bodyAs(response, AuthMessage.class);
      assertEquals("challenge", body.step);
      assertNotNull(body.nonce);
      assertTrue(body.nonce.startsWith("base64url:"));
    } finally {
      transport.close();
      emitter.shutdown();
    }
  }

  @Test
  void pairThenProofReturnsOkAndRegistersReceiver() throws Exception {
    int port = freePort();
    EmitterConfig info = emitterConfig("emitter-auth-pair-proof", port);
    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        info,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        null,
        TestTlsMaterial.serverSocketFactory(),
        null
    );
    emitter.setFirstPairingApprovalPolicy(request -> LanMetaRayEmitter.PairingApprovalDecision.ACCEPT);
    emitter.startAdvertising();
    emitter.startControlServer();

    EphemeralReceiverIdentityProvider receiverIdentity = new EphemeralReceiverIdentityProvider();
    ReceiverIdentity identity = receiverIdentity.currentIdentity();
    TlsControlTransport transport = TestTlsMaterial.newTransport(new InetSocketAddress(info.host, info.controlPort));
    try {
      WireMessage<?> pairRequired = sendAuth(transport, info.appId, identity, AuthMessage.hello(), identity.publicKey);
      AuthMessage pairRequiredBody = ControlWire.bodyAs(pairRequired, AuthMessage.class);
      assertEquals("pairRequired", pairRequiredBody.step);

      WireMessage<?> challengeAfterPair = sendAuth(transport, info.appId, identity, AuthMessage.pair(), identity.publicKey);
      AuthMessage challengeBody = ControlWire.bodyAs(challengeAfterPair, AuthMessage.class);
      assertEquals("challenge", challengeBody.step);
      assertNotNull(challengeBody.nonce);

      byte[] payload = AuthProof.canonicalPayload(info.emitterId, identity.keyId, challengeBody.nonce);
      String proof = AuthProof.encodeBase64Url(receiverIdentity.signAuthPayload(payload));
      WireMessage<?> ok = sendAuth(transport, info.appId, identity, AuthMessage.proof(challengeBody.nonce, proof), null);
      AuthMessage okBody = ControlWire.bodyAs(ok, AuthMessage.class);
      assertEquals("ok", okBody.step);
      assertNotNull(okBody.sessionId);
      assertFalse(okBody.sessionId.isBlank());
      assertEquals("cbor", ok.ext.get("metaray.negotiation.selectedCodec"));
      assertEquals("tls", ok.ext.get("metaray.negotiation.selectedSecurity"));
      assertTrue(emitter.isReceiverRegistered(identity.keyId));
    } finally {
      transport.close();
      emitter.shutdown();
    }
  }

  @Test
  void declinedFirstTimePairingDoesNotRegisterReceiver() throws Exception {
    int port = freePort();
    EmitterConfig info = emitterConfig("emitter-auth-pair-declined", port);
    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        info,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        null,
        TestTlsMaterial.serverSocketFactory(),
        null
    );
    emitter.startAdvertising();
    emitter.startControlServer();

    EphemeralReceiverIdentityProvider receiverIdentity = new EphemeralReceiverIdentityProvider();
    ReceiverIdentity identity = receiverIdentity.currentIdentity();
    TlsControlTransport transport = TestTlsMaterial.newTransport(new InetSocketAddress(info.host, info.controlPort));
    try {
      WireMessage<?> pairRequired = sendAuth(transport, info.appId, identity, AuthMessage.hello(), identity.publicKey);
      AuthMessage pairRequiredBody = ControlWire.bodyAs(pairRequired, AuthMessage.class);
      assertEquals("pairRequired", pairRequiredBody.step);

      WireMessage<?> deny = sendAuth(transport, info.appId, identity, AuthMessage.pair(), identity.publicKey);
      AuthMessage denyBody = ControlWire.bodyAs(deny, AuthMessage.class);
      assertEquals("deny", denyBody.step);
      assertEquals("userDeclined", denyBody.reason);
      assertFalse(emitter.isReceiverRegistered(identity.keyId));
    } finally {
      transport.close();
      emitter.shutdown();
    }
  }

  @Test
  void invalidProofReturnsDeny() throws Exception {
    int port = freePort();
    EmitterConfig info = emitterConfig("emitter-auth-invalid-proof", port);
    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        info,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        null,
        TestTlsMaterial.serverSocketFactory(),
        null
    );
    emitter.startAdvertising();
    emitter.startControlServer();

    EphemeralReceiverIdentityProvider receiverIdentity = new EphemeralReceiverIdentityProvider();
    ReceiverIdentity identity = receiverIdentity.currentIdentity();
    emitter.registerReceiver(identity.keyId, identity.id, identity.publicKey);

    TlsControlTransport transport = TestTlsMaterial.newTransport(new InetSocketAddress(info.host, info.controlPort));
    try {
      WireMessage<?> challenge = sendAuth(transport, info.appId, identity, AuthMessage.hello(), identity.publicKey);
      AuthMessage challengeBody = ControlWire.bodyAs(challenge, AuthMessage.class);
      assertEquals("challenge", challengeBody.step);
      WireMessage<?> deny = sendAuth(
          transport,
          info.appId,
          identity,
          AuthMessage.proof(challengeBody.nonce, "base64url:AA"),
          null
      );
      AuthMessage denyBody = ControlWire.bodyAs(deny, AuthMessage.class);
      assertEquals("deny", denyBody.step);
      assertEquals("invalidProof", denyBody.reason);
    } finally {
      transport.close();
      emitter.shutdown();
    }
  }

  @Test
  void pullWithoutAuthSessionIsRejected() throws Exception {
    int port = freePort();
    EmitterConfig info = emitterConfig("emitter-auth-reject-pull", port);
    LanMetaRayEmitter emitter = new LanMetaRayEmitter(
        info,
        new JacksonJsonCodec(),
        new JacksonCborCodec(),
        null,
        TestTlsMaterial.serverSocketFactory(),
        null
    );
    emitter.startAdvertising();
    emitter.startControlServer();

    JacksonJsonCodec codec = new JacksonJsonCodec();
    TlsControlTransport transport = TestTlsMaterial.newTransport(new InetSocketAddress(info.host, info.controlPort));
    try {
      WireMessage.Header header = new WireMessage.Header(
          info.appId,
          "req-1",
          null,
          "unknown-session",
          "sha256:unknown",
          null,
          null
      );
      WireMessage<PullMessage> request = new WireMessage<>(
          new WireMessage.Std<>(
              1,
              MsgType.PULL,
              header,
              new PullMessage("events", PullOptions.Mode.LIMIT, 50, List.of()),
              null,
              null),
          Map.of()
      );
      byte[] responseBytes = transport.request(codec.encode(request), Duration.ofSeconds(3)).join();
      WireMessage<?> response = ControlWire.decode(codec, responseBytes);
      assertEquals(MsgType.ERROR, response.std.msgType);
      ErrorMessage error = ControlWire.bodyAs(response, ErrorMessage.class);
      assertEquals("CONTROL_ERROR", error.errorCode);
    } finally {
      transport.close();
      emitter.shutdown();
    }
  }

  private static WireMessage<?> sendHello(TlsControlTransport transport, String appId, ReceiverIdentity identity) {
    return sendAuth(transport, appId, identity, AuthMessage.hello(), identity.publicKey);
  }

  private static WireMessage<?> sendAuth(
      TlsControlTransport transport,
      String appId,
      ReceiverIdentity identity,
      AuthMessage body,
      String publicKey
  ) {
    JacksonJsonCodec codec = new JacksonJsonCodec();
    WireMessage.Header header = new WireMessage.Header(
        appId,
        identity.id,
        null,
        null,
        identity.keyId,
        publicKey,
        null
    );
    WireMessage<AuthMessage> request = new WireMessage<>(
        new WireMessage.Std<>(1, MsgType.AUTH, header, body, null, null),
        Map.of(
            "metaray.negotiation.supportedCodecs", List.of("cbor", "json"),
            "metaray.negotiation.supportedSecurity", List.of("tls")
        )
    );
    byte[] responseBytes = transport.request(codec.encode(request), Duration.ofSeconds(3)).join();
    return ControlWire.decode(codec, responseBytes);
  }

  private static EmitterConfig emitterConfig(String instanceId, int port) {
    return new EmitterConfig(
        1,
        instanceId,
        "127.0.0.1",
        port,
        null,
        null,
        Set.of("json", "cbor"),
        Set.of("tls"),
        Set.of("assets", "actions")
    );
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
