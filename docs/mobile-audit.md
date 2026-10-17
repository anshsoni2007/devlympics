# SWAR mobile audit and one-hour delivery plan

## Audit (completed before implementation)

The repository contains a React 19/Vite presentation application, an Express/TypeScript backend, Vercel adapters and six Node tests. `packages`, `services`, and `bundle/models` have no usable source/model files in the inspected inventory. There is no Android project, SentencePiece model, ITP/1 implementation, CRC or FEC implementation.

Reusable work: the existing visual language and web prototype; four-byte stream framing; handshake, ACK/deduplication, queue and transport separation concepts. Preserve all existing code. The Node backend remains a development prototype, not a dependency of the Android application.

Critical findings:
- STTEngine returns clientHint rather than recognizing audio; confidence and minimum inference duration are synthetic.
- TTSEngine synthesizes harmonics rather than intelligible speech.
- WifiDirectTransport and BluetoothTransport use Node TCP sockets, not Android Wi-Fi Direct or RFCOMM.
- NsdDiscovery uses UDP beacons rather than platform NSD.
- /api/v1/runs substitutes emergency text when recognition is absent and returns fixed bitrate, latency and success values.
- Six existing tests pass, including a WAV format test; this does not establish real speech or networking support.

## Target and technology decision

User constraints: OnePlus 11R and Pixel 9a, Hindi/English/Hinglish, phones only with no internet during the demo, one-hour deadline.

Use native Android Java services with a locally bundled WebView presentation. This reuses the existing dark/indigo visual language and web UI approach while accessing Android speech and network APIs directly. Java avoids adding a Kotlin compiler or React Native/Flutter toolchain during the deadline. A Unity installation supplies SDK 36, JDK 17 and Gradle 9.1; AGP 9.0 is cached. The standalone app does not load the web prototype's backend, videos or remote fonts.

React Native/Expo would require native modules and a new runtime; Flutter requires another SDK; native Kotlin would be reasonable beyond this deadline. Framework migration is not the demonstration's critical path.

Transport comparison: hotspot/LAN TCP is the first implementation, with Android NSD and a manual address fallback for multicast-isolated hotspots. Wi-Fi Direct, Bluetooth and Nearby Connections introduce discovery/permission/pairing complexity. WebRTC needs signaling and a native dependency. UDP allows loss experiments but requires extra reliability. TCP already provides ordered reliable delivery; application ACKs mean decoded delivery, not audible playback. No mesh claim.

## File-level plan and migration

Create `mobile/` with Gradle build configuration, native protocol, LAN connection service, speech service, Activity bridge, bundled UI and protocol tests. Create `scripts/build-mobile.ps1` and mobile documentation. Preserve `apps/web/`, `backend/`, `api/`, and existing tests. No deletion is planned. Add a README notice distinguishing the old prototype from the actual Android path.

Implementation order: build skeleton; independently test binary framing/CRC/FEC; local speech availability; host/join/handshake; text delivery; connect speech; conversation gating; dashboard; APK; physical-device validation and failure tests.

## Architecture and constraints

UI -> Activity bridge -> independent SpeechEngine and LocalTransport -> ItpPacket codec. Android NSD discovers a phone-hosted TCP listener. CONTROL messages handle HELLO, capability exchange, PING/PONG and decoded ACK. ITP payloads carry session/sequence/language/codec metadata. All transcripts and identifiers are ephemeral; no audio files or permanent transcript logs. Background capture is excluded from this foreground demo.

Use only `createOnDeviceSpeechRecognizer`, never cloud-capable `createSpeechRecognizer`. The system recognizer owns microphone capture and its speech-boundary detector. This provides platform speech-start/end events, not a separately configurable Silero pipeline. Offline language availability must be checked on each handset; the app must fail visibly if unsupported. Use only TTS voices reporting `isNetworkConnectionRequired == false`. Hinglish is an experimental Hindi recognition profile, not a validated independent model. Large IndicConformer/IndicF5 models are not integrated in the one-hour build.

SentencePiece is absent. Initial explicit codec is lossless UTF-8 with optional DEFLATE, not semantic VQ or SentencePiece. This is a disclosed incomplete requirement, never relabeled tokenization. New ITP/1 draft must document this codec ID and is not claimed compatible with an absent previous implementation.

## Verification and completion

Unit tests: Unicode round trips, CRC rejection, single-bit FEC repair, double-bit rejection, malformed lengths and invalid versions. Integration: local TCP handshake, control/data separation, ACK, duplicate suppression, timeout/reconnect. Device checks: launch on both phones, microphone permissions, installed Hindi/English speech, TTS, hotspot without internet, both directions, PTT release, conversation feedback gating, dropped peer/reconnect. Failure tests must distinguish automated checks from unperformed handset checks.

Demo: install same APK; prepare offline speech voices/models; disable cellular data and disconnect external Wi-Fi; turn on one phone's hotspot; join from the other; open both apps; host/discover/connect; send typed text to prove the link; speak Hindi and English with PTT; test Hinglish; inspect measured ACK RTT and bytes; reverse direction; test conversation; disconnect/rejoin. Typed messages are a transport fallback, not proof of STT.

A built APK alone does not satisfy agent.md. Physical phone discovery, offline Hindi/English/Hinglish speech and receiver playback must be observed before declaring the MVP complete. Radio-level packet loss, physical network bitrate, battery consumption and end-to-end acoustic latency require separate measurement; do not substitute application byte counters or ACK RTT.
