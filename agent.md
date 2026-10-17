# GPT Astra Prompt — SWAR Mobile Emergency Communication MVP

You are an expert AI systems architect, mobile engineer, networking engineer, ML engineer, and hackathon product engineer.

I am building **SWAR — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low-Bitrate Links**.

The current project/specification is oriented around a web/backend prototype, but the actual MVP must now become a **mobile emergency communication application**.

Your job is to **audit the existing repository first, then re-architect and implement the mobile MVP without destroying useful existing work**.

---

# 1. PRODUCT VISION

SWAR is an AI-assisted emergency communication system designed to make speech more efficient and resilient over constrained communication links.

The conceptual pipeline is:

```text
Speech
  ↓
Voice Activity Detection
  ↓
Speech-to-Text
  ↓
Semantic / Text Representation
  ↓
Low-Bitrate Compression / Tokenization
  ↓
ITP/1 Packetization
  ↓
CRC + FEC
  ↓
Communication Transport
  ↓
CRC + FEC Recovery
  ↓
Text / Semantic Reconstruction
  ↓
Text-to-Speech
  ↓
Recovered Speech
```

The MVP should be **transcript-first semantic radio**, not a fake end-to-end neural physical-layer modem.

The system must clearly distinguish:

- SWAR configured payload bitrate
- actual network bitrate
- protocol overhead
- packet size
- compression ratio
- latency
- packet loss
- recovered-message quality

Do not claim that Wi-Fi/Bluetooth itself operates at the configured SWAR semantic bitrate.

---

# 2. PRIMARY MVP REQUIREMENTS

The mobile application must support:

## A. Walkie-Talkie Mode

Users should be able to connect nearby phones/devices and communicate using push-to-talk.

Expected flow:

```text
Press and hold microphone
        ↓
Start audio capture
        ↓
VAD detects speech
        ↓
STT
        ↓
Compress/tokenize text
        ↓
ITP/1 packet creation
        ↓
CRC/FEC
        ↓
Local transport
        ↓
Receiver
        ↓
Decode
        ↓
TTS
        ↓
Play recovered speech
```

Requirements:

- Push-to-talk button
- Press-and-hold interaction
- Release stops transmission
- VAD-assisted speech detection
- Visual transmission state
- Receiver playback
- Message/packet status
- Latency measurement
- Packet statistics
- Recovery/error information

---

# 3. AI VOICE DETECTION

Implement AI-assisted Voice Activity Detection.

The system must determine:

- when speech starts
- when speech continues
- when speech ends
- when silence occurs
- whether captured audio is meaningful speech

Recommended options:

- Silero VAD
- WebRTC VAD
- platform-native VAD where appropriate

Create configurable parameters:

- speech threshold
- minimum speech duration
- silence timeout
- pre-roll
- post-roll

Emit events such as:

```text
SPEECH_STARTED
SPEECH_CONTINUING
SPEECH_ENDED
```

Do not build a fake VAD.

If the chosen model cannot run efficiently on-device, design the architecture so it can run through a local backend/service while preserving offline operation where possible.

---

# 4. CONVERSATION MODE

In addition to push-to-talk, implement a persistent conversation mode.

This mode should allow two or more users to maintain a longer communication session.

Required features:

- persistent connection
- near-real-time or streaming STT
- continuous packet transmission
- TTS playback
- turn detection
- interruption handling
- reconnect handling
- session timeout
- connection state
- sequence numbers
- packet ordering
- duplicate detection
- audio playback control

Avoid feedback loops such as:

```text
Phone A speaks
→ Phone B TTS plays
→ Phone B microphone captures TTS
→ Phone B transmits it again
```

Use appropriate microphone/playback state management, VAD gating, and echo-control strategies.

---

# 5. OFFLINE COMMUNICATION IS CRITICAL

The emergency mode must work **without internet access**.

Do NOT secretly route offline communication through a cloud backend.

The communication architecture must support:

```text
ONLINE MODE

Phone A
   ↓
Internet / Wi-Fi
   ↓
Backend WebSocket
   ↓
Phone B
```

and:

```text
OFFLINE MODE

Phone A
   ↓
Local discovery
   ↓
Local Wi-Fi / hotspot / LAN / P2P
   ↓
Phone B
```

The mobile application must bypass the internet backend in true offline mode.

---

# 6. CHOOSE A PRACTICAL OFFLINE TRANSPORT

Before implementing networking, evaluate:

- Wi-Fi Direct
- local Wi-Fi LAN
- phone hotspot
- Bluetooth
- Nearby Connections
- WebRTC local transport
- UDP
- TCP

For the hackathon MVP, prioritize **reliability and implementation speed**.

If a true decentralized mesh is too complex, use:

```text
Local hotspot / LAN
+
local discovery
+
direct device-to-device communication
```

or a simple local coordinator.

Do NOT spend the majority of the MVP building a full Bluetooth mesh network.

The MVP should prove that:

```text
Phone A ↔ Phone B
```

works physically with no internet.

Ideally support:

```text
Phone A ↔ Phone B ↔ Phone C
```

for a basic group communication demo.

---

# 7. DEVICE DISCOVERY

Implement a local discovery protocol.

Suggested state flow:

```text
DISCOVERY
   ↓
HELLO
   ↓
CAPABILITY EXCHANGE
   ↓
SESSION NEGOTIATION
   ↓
CONNECTED
```

Exchange:

- device ID
- display name
- language
- supported transport
- codec version
- bitrate profile
- VAD support
- STT capability
- TTS capability
- protocol version

Do not expose unnecessary personal information.

---

# 8. CONNECTION MANAGER

Create a dedicated connection manager.

Connection states:

```text
DISCOVERING
CONNECTING
CONNECTED
DEGRADED
RECONNECTING
DISCONNECTED
FAILED
```

Implement:

- heartbeat
- connection timeout
- reconnect
- session ID
- sequence numbers
- duplicate detection
- packet ordering
- failure handling
- connection quality monitoring

The UI must clearly display the current connection state.

---

# 9. MULTI-USER SUPPORT

At minimum:

```text
Device A ↔ Device B
```

Preferably:

```text
Device A
   ↕
Device B
   ↕
Device C
```

Support a basic group/walkie channel if practical.

If decentralized routing becomes too complex, implement a local coordinator architecture for the MVP.

Do not pretend it is a fully decentralized mesh if it is not.

---

# 10. PRESERVE THE SWAR PROTOCOL

The existing SWAR semantic transport concept should remain the core of the communication layer.

Use:

```text
CONTROL PROTOCOL
```

for:

- discovery
- session setup
- heartbeat
- capability exchange
- disconnect
- reconnect

Use:

```text
ITP/1
```

for:

- semantic/text payload transmission
- packetization
- CRC
- FEC
- sequence numbers
- payload metadata

Keep these two layers separate.

---

# 11. LOW-BITRATE PROFILES

Support configurable semantic payload profiles such as:

```text
0.5 kbps
1 kbps
2 kbps
4 kbps
8 kbps
16 kbps
```

These are **SWAR payload/semantic targets**, not claims about physical Wi-Fi or Bluetooth speed.

Measure separately:

```text
Configured SWAR bitrate
Actual transport bitrate
Protocol overhead
Effective payload bitrate
```

The dashboard should make this distinction obvious.

---

# 12. AI PIPELINE

Implement the MVP in layers.

## Mode 1 — Fast MVP

```text
Audio
 ↓
VAD
 ↓
STT
 ↓
Text
 ↓
SentencePiece / token representation
 ↓
ITP/1
 ↓
CRC + FEC
 ↓
Transport
 ↓
ITP/1 decode
 ↓
Text
 ↓
TTS
 ↓
Audio
```

This should be the primary working implementation.

## Mode 2 — Semantic VQ

Allow future integration of:

```text
Text
 ↓
Semantic Encoder
 ↓
Vector Quantization
 ↓
Compact semantic representation
 ↓
ITP
 ↓
Decoder
 ↓
Recovered text
```

## Mode 3 — Research DeepJSCC

Keep a research extension point for:

```text
Encoder
 ↓
Neural channel representation
 ↓
Differentiable channel
 ↓
Neural decoder
```

Do NOT make this the core MVP.

---

# 13. STT

Support Indian multilingual speech.

Preferred architecture:

- AI4Bharat IndicConformer for Indian languages
- faster-whisper for English/general fallback where appropriate

The system should support multiple Indian languages and Hinglish where feasible.

The architecture must make STT providers pluggable.

Example:

```text
STTProvider
 ├── IndicConformerProvider
 ├── WhisperProvider
 └── LocalFallbackProvider
```

Do not hard-code the entire application to one STT model.

---

# 14. TTS

Support Indian-language speech reconstruction.

Preferred architecture:

- AI4Bharat IndicF5 or the best compatible local Indian-language TTS implementation available in the existing project
- Piper or another local lightweight fallback for English if useful

Architecture:

```text
TTSProvider
 ├── IndicTTSProvider
 ├── EnglishTTSProvider
 └── LocalFallbackProvider
```

TTS should be replaceable.

---

# 15. SEMANTIC TOKENIZATION

Use SentencePiece for deterministic tokenization in the MVP.

Example:

```text
Speech
→ STT text
→ SentencePiece tokens
→ compact representation
→ ITP packet
```

Do not add an LLM just to tokenize text.

An LLM is optional for future semantic intelligence, not required for the basic MVP.

---

# 16. MOBILE TECHNOLOGY DECISION

Audit the existing repository before deciding.

Evaluate:

- React Native / Expo
- Flutter
- native Android/Kotlin

Decision criteria:

1. microphone access
2. local networking
3. offline operation
4. background/audio handling
5. on-device ML compatibility
6. latency
7. Android hardware testing
8. hackathon implementation speed

For the MVP, choose the stack that provides the most reliable Android physical-device demo.

If necessary, prefer:

```text
Android + Kotlin
```

for low-level networking/audio control.

If the existing repository already has a strong React/TypeScript architecture and React Native provides adequate native integration, React Native can be used.

Do not choose a framework purely because it is fashionable.

Document the decision.

---

# 17. MOBILE UI

Create a polished emergency-tech mobile UI.

Required screens:

## Home

Show:

- SWAR logo/name
- Emergency Communication
- Walkie-Talkie
- Conversation Mode
- Nearby Devices
- Connection state
- Offline/Online status

## Device Discovery

Show:

- nearby devices
- signal/connection quality
- device name
- language
- supported capabilities
- connect button

## Walkie-Talkie

Large push-to-talk button.

Show:

- microphone state
- VAD state
- connected peer
- language
- current bitrate
- packets sent
- packets received
- packet loss
- latency
- compression ratio

## Conversation

Show:

- persistent connection
- live transcript
- speaking indicator
- remote speaking indicator
- TTS playback
- turn state
- connection quality

## Network / Radio Dashboard

Show:

- transport
- semantic bitrate
- actual transport bitrate
- packet size
- packets sent
- packets received
- packet loss
- CRC failures
- FEC recovery
- latency
- jitter

## AI Dashboard

Show:

- VAD
- STT model
- detected language
- TTS model
- token count
- compression
- inference latency

## Settings

Allow configuration of:

- language
- bitrate profile
- transport
- VAD sensitivity
- TTS voice
- offline mode
- diagnostics

---

# 18. AUDIO PIPELINE

Use:

```text
PCM
16-bit
mono
16 kHz
```

where supported by the selected models.

Pipeline:

```text
Microphone
 ↓
Audio Buffer
 ↓
VAD
 ↓
Speech Segment
 ↓
STT
 ↓
Text
```

The audio layer should expose clean interfaces.

Example:

```text
AudioCapture
AudioBuffer
AudioProcessor
VADProcessor
```

Avoid putting audio logic directly inside UI components.

---

# 19. BACKEND ARCHITECTURE

The backend remains useful for:

- online sessions
- model inference
- development
- diagnostics
- benchmarking
- optional heavy-model execution

Use:

```text
Python
FastAPI
Uvicorn
```

REST can provide:

```text
GET /health
GET /models
GET /config
POST /sessions
GET /sessions/{id}
```

WebSocket can support live online communication.

However:

**The backend must NOT be required for offline phone-to-phone communication.**

---

# 20. COMMUNICATION ABSTRACTION

Create a transport abstraction:

```text
Transport
 ├── OnlineWebSocketTransport
 ├── LocalLANTransport
 ├── WiFiDirectTransport
 ├── BluetoothTransport
 └── MockTransport
```

Only implement the transports that are realistically required for the MVP.

The upper layers should not care which transport is used.

Example:

```text
CommunicationService
        ↓
Transport Interface
        ↓
Online / Offline Transport
```

---

# 21. RECOMMENDED ARCHITECTURE

Use this conceptual architecture:

```text
┌─────────────────────────────┐
│        Mobile App           │
│                             │
│ Home / Walkie / Conversation│
│ Dashboard / Settings        │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│    Communication Layer      │
│                             │
│ Discovery / Sessions        │
│ Connection Manager          │
│ Transport Abstraction       │
└──────────────┬──────────────┘
               │
       ┌───────┴────────┐
       ▼                ▼
┌──────────────┐  ┌──────────────┐
│ Online       │  │ Offline      │
│ WebSocket    │  │ Local LAN/P2P│
│ Backend      │  │ Direct       │
└──────────────┘  └──────┬───────┘
                         │
                         ▼
┌────────────────────────────────┐
│          SWAR Core          │
│                                │
│ VAD → STT → Tokenizer → ITP/1 │
│ → CRC/FEC → Decode → TTS       │
└────────────────────────────────┘
```

---

# 22. REPOSITORY STRUCTURE

Refactor toward something similar to:

```text
/
├── mobile/
│   ├── src/
│   │   ├── screens/
│   │   ├── components/
│   │   ├── services/
│   │   ├── audio/
│   │   ├── communication/
│   │   ├── offline/
│   │   ├── state/
│   │   └── models/
│   └── tests/
│
├── backend/
│   ├── api/
│   ├── websocket/
│   ├── sessions/
│   ├── inference/
│   └── services/
│
├── core/
│   ├── protocol/
│   ├── codec/
│   ├── fec/
│   ├── channel/
│   ├── audio/
│   ├── vad/
│   ├── metrics/
│   └── models/
│
├── models/
│
├── tests/
│
├── docs/
│
├── scripts/
│
└── README.md
```

Preserve existing useful modules rather than blindly replacing them.

---

# 23. TESTING

You must test on real Android devices.

Minimum tests:

### Test 1

```text
Phone A → Phone B
```

### Test 2

```text
Phone A ↔ Phone B
Conversation mode
```

### Test 3

```text
Phone A → Phone B → Phone C
```

if group communication is implemented.

### Test 4

Disable internet completely.

Confirm:

```text
Phone A ↔ Phone B
```

still communicates.

### Test 5

Simulate:

- packet loss
- latency
- jitter
- reconnect
- device disappearance
- weak local network
- duplicate packets
- out-of-order packets

### Test 6

Test multiple Indian languages.

### Test 7

Test Hinglish.

### Test 8

Test interruption:

```text
User A speaking
→ User B starts speaking
```

The system must handle this gracefully.

---

# 24. METRICS

Measure:

## Communication

- packets sent
- packets received
- packet loss
- CRC failures
- FEC recovery
- retransmissions if used
- effective payload bitrate
- actual transport bitrate

## AI

- VAD latency
- STT latency
- TTS latency
- total inference latency
- token count
- compression ratio

## End-to-End

Measure:

```text
Speech start
        ↓
STT completed
        ↓
Packet transmitted
        ↓
Packet decoded
        ↓
TTS started
        ↓
Audio played
```

Report:

- end-to-end latency
- median latency
- p95 latency where enough samples exist

Do not fabricate performance numbers.

---

# 25. EMERGENCY MODE

Emergency mode should prioritize:

1. local communication
2. minimal dependencies
3. low bandwidth
4. battery awareness
5. connection visibility
6. clear UI
7. failure recovery

Display:

```text
OFFLINE MODE
CONNECTED TO 2 DEVICES
BATTERY
TRANSPORT
SIGNAL / LINK QUALITY
CURRENT BITRATE
PACKET LOSS
```

Avoid unnecessary animations and network requests.

---

# 26. SECURITY

Implement reasonable MVP security:

- device/session IDs
- message integrity
- CRC
- authenticated session setup where practical
- transport encryption where available
- no permanent audio storage by default
- temporary transcript storage only
- explicit opt-in for diagnostics

Do NOT claim:

- military-grade encryption
- unbreakable security
- guaranteed emergency connectivity

unless actually implemented and independently verified.

---

# 27. PRIVACY

Default behavior:

```text
Audio
→ process
→ transmit semantic representation
→ discard temporary data
```

Do not permanently store:

- raw microphone recordings
- transcripts
- user identifiers

unless the user explicitly enables storage/diagnostics.

---

# 28. DEMO MODE

The hackathon demo must be extremely reliable.

Recommended demo:

### Step 1

Launch SWAR on Phone A and Phone B.

### Step 2

Disable internet.

### Step 3

Both phones enter emergency/offline mode.

### Step 4

Phone A discovers Phone B.

### Step 5

Connect.

### Step 6

Phone A presses the walkie-talkie button.

### Step 7

Speak:

```text
"Help is needed at the main gate."
```

### Step 8

Show:

```text
VAD detected speech
STT completed
Language detected
Text tokenized
ITP packet created
CRC generated
FEC applied
Packet transmitted
```

### Step 9

Phone B receives.

### Step 10

Show:

```text
Packet received
CRC verified
FEC status
Text reconstructed
TTS generated
```

### Step 11

Phone B plays the recovered speech.

### Step 12

Open the radio dashboard and show:

- bitrate
- packet size
- latency
- packet loss
- compression
- language

### Step 13

Switch to Conversation Mode.

### Step 14

Demonstrate a continuous exchange.

---

# 29. FAILURE HANDLING

Every network operation must have a failure state.

Handle:

```text
Device unavailable
Connection timeout
Connection dropped
Invalid packet
CRC failure
FEC failure
Unsupported language
STT failure
TTS failure
Microphone permission denied
Speaker unavailable
Transport unavailable
Model unavailable
```

The UI must provide understandable errors.

Do not crash the application.

---

# 30. IMPORTANT ENGINEERING RULES

1. Audit the existing code before editing.
2. Do not delete useful working components.
3. Do not rewrite everything without justification.
4. Build the smallest end-to-end working system first.
5. Test on real Android devices early.
6. Do not make the cloud backend a hidden dependency.
7. Do not fake offline functionality.
8. Do not fake AI capabilities.
9. Do not fabricate metrics.
10. Keep transport independent from AI.
11. Keep protocol logic independent from UI.
12. Keep STT/TTS providers replaceable.
13. Keep VAD configurable.
14. Make the system observable.
15. Prioritize a reliable demo over research complexity.

---

# 31. DO NOT BUILD YET

Do NOT spend MVP time on:

- global mesh networking
- satellite communication
- custom RF hardware
- military radio hardware
- nationwide routing
- blockchain
- social networking
- unnecessary cloud infrastructure
- complicated distributed consensus
- a full DeepJSCC research system
- unnecessary LLM integration

These can remain future/research extensions.

---

# 32. DEVELOPMENT PHASES

Implement in this order.

## Phase 1 — Repository Audit

Inspect:

- files
- architecture
- dependencies
- existing APIs
- protocol implementation
- ML modules
- frontend
- tests
- configuration

Produce:

```text
CURRENT ARCHITECTURE
WHAT CAN BE REUSED
WHAT MUST CHANGE
WHAT IS MISSING
RISKS
IMPLEMENTATION PLAN
```

Do not modify code during the audit.

---

## Phase 2 — Mobile Skeleton

Create:

- Android mobile application
- navigation
- home screen
- walkie screen
- conversation screen
- discovery screen
- dashboard
- settings

Get it running on a physical Android device.

---

## Phase 3 — Audio + VAD

Implement:

```text
Microphone
→ PCM
→ VAD
→ Speech segment
```

Test this independently.

---

## Phase 4 — STT

Implement:

```text
Speech
→ STT
→ text
```

Test Indian languages.

---

## Phase 5 — SWAR Core

Implement:

```text
Text
→ SentencePiece
→ ITP/1
→ CRC
→ FEC
→ decode
```

Unit test every stage.

---

## Phase 6 — Offline Communication

Implement:

```text
Discovery
→ Connect
→ Handshake
→ Capability exchange
→ Packet transmission
```

First achieve:

```text
A ↔ B
```

without internet.

---

## Phase 7 — End-to-End Walkie-Talkie

Connect:

```text
Mic
→ VAD
→ STT
→ ITP
→ Transport
→ Decode
→ TTS
→ Speaker
```

Test with physical phones.

---

## Phase 8 — Conversation Mode

Add:

- persistent sessions
- turn detection
- interruption handling
- reconnect
- continuous communication

---

## Phase 9 — Dashboard

Add live metrics.

---

## Phase 10 — Hardening

Test:

- offline
- packet loss
- reconnect
- multiple languages
- device failure
- permissions
- low battery
- weak connection

---

# 33. DEFINITION OF MVP COMPLETE

The MVP is complete only when all of the following work:

```text
[ ] Android application launches
[ ] Two physical phones can discover each other
[ ] Phones can connect
[ ] Works without internet
[ ] Push-to-talk works
[ ] Microphone capture works
[ ] VAD works
[ ] STT works
[ ] Indian language support works
[ ] SentencePiece/tokenization works
[ ] ITP/1 works
[ ] CRC works
[ ] FEC works
[ ] Packets can be transmitted
[ ] Packets can be decoded
[ ] TTS works
[ ] Receiver can hear reconstructed speech
[ ] Conversation mode works
[ ] Connection recovery works
[ ] Metrics are visible
[ ] Errors are handled
[ ] No cloud dependency exists in offline mode
```

---

# 34. EXPECTED OUTPUT FROM YOU

After auditing the repository, produce:

## A. Architecture Report

Explain:

- current architecture
- target architecture
- migration strategy
- components reused
- components replaced
- missing components

## B. Technology Decision

Explain why the selected mobile stack is appropriate.

## C. Implementation Plan

Give exact steps in order.

## D. File-Level Plan

Identify:

- files to create
- files to modify
- files to preserve
- files to delete only if genuinely unnecessary

## E. Protocol Specification

Document:

- CONTROL protocol
- ITP/1
- packet structure
- sequence numbers
- CRC
- FEC
- transport abstraction

## F. Mobile Architecture

Document:

- screens
- services
- state management
- audio pipeline
- networking
- offline behavior

## G. AI Architecture

Document:

- VAD
- STT
- tokenization
- TTS
- optional semantic VQ

## H. Testing Plan

Include:

- unit tests
- integration tests
- physical device tests
- offline tests
- network failure tests

## I. Demo Plan

Give an exact step-by-step hackathon demonstration.

## J. Known Limitations

Be honest about:

- Android platform limitations
- local networking limitations
- model size
- inference latency
- battery usage
- supported languages
- packet reliability
- transport constraints

---

# 35. FINAL ARCHITECTURAL PRINCIPLE

The most important design principle is:

> **Do not just collect real-world communication data and display it inside an AI demo. Make the communication environment actually change how the system behaves.**

For SWAR, this means:

```text
Real-world voice
      ↓
AI understands it
      ↓
AI compresses the semantic content
      ↓
Communication layer adapts to constrained conditions
      ↓
Receiver reconstructs the message
      ↓
AI converts it back into natural speech
```

The mobile application should make this entire loop visible, measurable, and demonstrable.

Build the MVP around **real phone-to-phone communication**, **offline capability**, **AI voice processing**, and **low-bit-rate semantic transmission**.

Do not over-engineer the research components before the basic emergency communication loop works end-to-end.
