# Offline Voice Bridge (SWAR)

This document describes the native Android offline voice bridge implementation in `mobile/offline`, built according to [final.md](../final.md) and [answers.txt](../answers.txt).

---

## 1. System Architecture

The offline implementation operates completely without internet connectivity, external servers, or cloud dependencies.

```
+--------------------------------------------------------------------------------+
|                               Android Application                              |
|                              (in.itantra.offline)                              |
+--------------------------------------------------------------------------------+
| Native UI: OfflineActivity (Full-Duplex Conversation vs. Walkie-Talkie PTT)   |
+--------------------------------------------------------------------------------+
| Session Layer: OfflineSession & FloorCoordinator (Epochs, Leases, Elections)   |
+--------------------------------------------------------------------------------+
| Speech Pipeline:                                                               |
|  - Capture: AudioRecord (16 kHz 16-bit mono) with AEC / NoiseSuppressor        |
|  - VAD: Silero VAD v5 ONNX (intra-utterance micro-pauses)                      |
|  - STT: sherpa-onnx OnlineRecognizer (Cache-aware NeMo CTC / IndicConformer)   |
|  - Stable Text: Incremental commitment & chunk offset tracking                 |
|  - TTS: ONNX Runtime (Indic-TTS FastPitch acoustic + HiFi-GAN vocoder)         |
|  - Playback: AudioTrack (Float PCM, streaming mode, volume ducking)            |
+--------------------------------------------------------------------------------+
| Security Layer:                                                                |
|  - Mutual Pairing: Ephemeral X25519 key exchange + HKDF 6-digit SAS code       |
|  - Transport Security: ChaCha20-Poly1305 AEAD authenticated encryption        |
|  - Identity: Ed25519 key pairs with KnownPeersStore persistent trust           |
|  - Replay Protection: Monotonic sequence numbers & direction separation        |
+--------------------------------------------------------------------------------+
| Transport Layer (TransportManager):                                            |
|  - Primary: Wi-Fi Direct (P2P Group Owner / Client with auto leader election)  |
|  - Secondary: Wi-Fi Aware (NAN publish / subscribe fallback)                   |
|  - Fallback: Bluetooth Low Energy (GATT peripheral / central + fragmentation)  |
+--------------------------------------------------------------------------------+
```

---

## 2. Core Capabilities

### A. Strict Full-Duplex Conversation Mode
- Simultaneous two-way audio capture, recognition, transmission, reception, synthesis, and playback.
- No automatic turn-taking, half-duplex mute, or audio pause during remote speech playback.
- Hardware acoustic echo cancellation (`AcousticEchoCanceler`) enabled where supported; non-blocking advisory presented if unavailable.
- Bounded playback volume ducking (10–12 dB) during active local speech to suppress acoustic coupling.

### B. Push-to-Talk (PTT) Walkie-Talkie Mode
- Supports up to 8 concurrent devices per channel.
- Single speaker floor coordination using deterministic leases with automatic timeout.
- Autonomous leader re-election: if the Group Owner / Coordinator drops, remaining peers deterministically elect the peer with the lowest lexicographical Device ID.
- Seamless BLE fallback: if Wi-Fi Direct drops, communication continues over BLE with finalized text messages.

### C. Stable-Token Incremental Playback & Deduplication
- Recognizer outputs stable token chunks with `stream_id`, `chunk_seq`, and `start_token_offset`.
- Receiver incrementally synthesizes stable partials.
- `ReceiveLedger` tracks committed text and reconciles with `stt_final`, synthesizing only the unplayed tail tokens and preventing repeated speech.

### D. End-to-End Security & Mutual Verification
- 6-Digit Short Authentication String (SAS) generated via HKDF over ephemeral key exchange transcript.
- Mutual out-of-band confirmation avoids Man-in-the-Middle (MitM) attacks.
- Once verified, persistent identities are saved in `KnownPeersStore` for zero-click authenticated reconnection.

### E. Offline Model Packaging (`.ilp`)
- Atomic ZIP-based model packages with SHA-256 verification and strict schema manifests.
- Separate STT and TTS capability advertisement.
- Base APK includes Silero VAD and native runtime libraries (< 100 MB target; actual binary 53.6 MB).
- Language models (Hindi, English, Odia starter packs; Bengali, Tamil, Telugu, Marathi offline packs) installed via local file import or P2P transfer.

---

## 3. Build & Test Instructions

### Prerequisites
- JDK 17 or higher (`JAVA_HOME`).
- Android SDK with platform `android-36` and `build-tools;36.0.0` (or Unity Android toolchain).
- Python 3.10+ for runtime preparation.

### Steps

1. **Prepare pinned open-source native runtimes:**
   ```powershell
   python .\scripts\prepare-offline-runtime.py
   ```
   *Downloads and verifies `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` and `silero_vad.onnx` using SHA-256 checksums.*

2. **Run automated tests, lint, and build the APK:**
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\build-offline.ps1
   ```

3. **Verify build artifacts:**
   - **APK Path:** `mobile/offline/build/outputs/apk/debug/offline-debug.apk`
   - **APK Size:** ~53.6 MB (within < 100 MB budget)
   - **Unit Tests:** 22 passing tests (`CryptoTest`, `ProtocolTest`, `SpeechAndFloorTest`, `TransportTest`, `ModelPackTest`).
   - **Android Lint:** 0 errors.

4. **Install to connected Android device (optional):**
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\build-offline.ps1 -Install
   ```
