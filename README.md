# Meta-ray

Meta-ray is a local-first protocol and SDK for second-screen and nearby device interactions on the same Wi-Fi network.

## Protocol Overview

Meta-ray is a local Wi-Fi multicast context protocol that lets TVs and IoT devices broadcast metadata to many nearby mobile devices, enabling second-screen experiences, commerce interactions, and AI assistant control - while providing an encrypted unicast backchannel for interfacing, personalization, and acknowledgements.

Meta-ray uses two complementary channels:

- `MEC` (Multicast Event Channel, emitter -> many): low-latency encrypted event broadcast.
- `UBCC` (Unicast Bidirectional Control Channel, receiver <-> emitter): encrypted request/response for `auth`, `pull`, and actions/assets.

Design rule:
Multicast fires events “to whom it may concern”; unicast `pull` is a private conversation.

## Philosophy

### Context Frame

`ContextFrame` is the one event/state payload type used across push and pull.
`ContextFrame.Std` uses required `contextId`, required `isEvent`, and optional `eventId`.

### Emitter

Emitter responsibilities:
- advertise discovery metadata
- serve encrypted control auth/pull/actions
- push context events via `pushEvent(ContextFrame)`

### Receiver

Receiver responsibilities:
- discover and connect
- complete auth handshake
- receive multicast event hints
- pull the latest authoritative context when needed

## Notes

- Control channel security in current runtime is `tls`.
- Pairing is key-based: known receiver keys reconnect without first-time pair; unknown keys require explicit `ACCEPT` during auth/pair flow.
- Devices must be on the same LAN with multicast allowed.

## Licensing

Meta-ray is available under two licenses:

• Meta-ray Community License – free for non-commercial use  
• Meta-ray Commercial License – required for commercial deployments

For commercial licensing inquiries:
sales@abioticai.com