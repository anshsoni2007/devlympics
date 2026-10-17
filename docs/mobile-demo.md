# SWAR: install and test the phone-only demo

## Build and install

From the repository root in PowerShell:

```powershell
./scripts/test-mobile-protocol.ps1
./scripts/build-mobile.ps1
./scripts/build-mobile.ps1 -Install -Device YOUR_ADB_SERIAL
```

Output: `mobile/app/build/outputs/apk/debug/app-debug.apk`. The script uses the Android SDK, JDK and Gradle already installed with Unity. Alternatively open `mobile/` in Android Studio and configure a local SDK 36. Minimum Android is 12/API 31. This debug APK is for development testing.

Install the same APK on the OnePlus 11R and Pixel 9a. USB installation requires Developer options > USB debugging and accepting this computer's debugging prompt. If USB is unavailable, transfer the APK to each phone and install it using the phone's package installer.

## Prepare offline speech before disabling internet

1. Open SWAR > Dashboard on each phone.
2. Check whether **On-device recognizer** is available. If unavailable, speech cannot run in this build; there is deliberately no cloud fallback.
3. Select Hindi on Walkie, then use **Prepare offline model** on Dashboard if needed. Repeat for English. Downloads depend on the installed Android recognizer. **Recheck installed speech** displays reported installed languages; service availability alone is not proof of Hindi support.
4. Open **TTS voice settings**, install Hindi and English voice data using the device's speech engine controls, and recheck. Only voices marked as not requiring a network are used.
5. Hinglish selects Hindi recognition. Recognition of mixed speech must be tried on the actual handset; no accuracy promise is made.

Android's strictly on-device recognition factory is used, not a generic recognizer with an offline preference alone. See [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer). TTS selects only [Voice entries that do not require a network](https://developer.android.com/reference/android/speech/tts/Voice).

## Two-phone demo sequence

1. Disable mobile data on both phones. Disconnect from external Wi-Fi. Keep Wi-Fi/hotspot radios enabled; airplane mode may disable the hotspot.
2. Enable a password-protected hotspot on Phone A. Connect Phone B to it. Accept “stay connected” if Android warns the network has no internet.
3. On Phone A: Nearby > **Host a local session**. Note its address and six-digit joining code.
4. On Phone B: Nearby > **Find nearby SWAR phones**. Tap Phone A, enter its code, and Connect. If discovery finds nothing, enter the private IPv4 address displayed on A. Some hotspots suppress multicast discovery.
5. Both apps should show CONNECTED. Send a typed message first, check the receiving transcript and decoded ACK. This proves only the transport path.
6. Select English, press and hold the microphone, say “Help is needed at the main gate,” and release. Verify that the receiver shows your actual words and plays speech. No default phrase is substituted if recognition fails.
7. Reverse direction. Repeat in Hindi: “मुख्य द्वार पर मदद चाहिए।” Try Hinglish: “Main gate पर help चाहिए.” Judge what the phones actually recognize.
8. Open Dashboard. Inspect packet counts, framed bytes, payload throughput, heartbeat RTT, CRC and FEC counters. With a healthy TCP link, zero corrected errors is expected; do not claim live FEC recovery without injecting corruption.
9. Start Conversation on both phones. Use short alternating phrases. Incoming TTS cancels unfinished local capture and plays, then listening resumes. Repeat interrupted phrases. This is gated alternating speech, not simultaneous full-duplex audio.
10. Turn the client's Wi-Fi off briefly, then reconnect it. Observe reconnection or reconnect manually after bounded retries. Messages without a decode ACK remain uncertain and are not automatically replayed.

Keep both apps visible. Navigating to another app pauses capture/conversation/playback. There is no microphone foreground service in this build. No laptop or backend participates in the phone demo.

## Honest current limits

- This is a scoped demo candidate, not the complete agent.md MVP.
- Offline Hindi/English/Hinglish depend on installed device speech services; no bundled IndicConformer/IndicF5/Whisper models.
- VAD is provided by the recognizer; separate model, threshold, pre/post-roll controls and explicit PCM buffer handling are not integrated.
- Lossless UTF-8/DEFLATE replaces the not-yet-integrated SentencePiece model.
- Two peers, LAN/hotspot only. No Bluetooth, Wi-Fi Direct, mesh, group routing or online WebSocket transport in this APK.
- Messages/transcripts and IDs live in memory, with a bounded visible history. No audio is written to files by the app.
- No app-level encryption, guaranteed delivery, physical network bitrate, radio loss or acoustic latency claims.

## Validation record

- Existing Node suite: 6 tests passed before migration.
- Android debug APK: built successfully locally.
- Protocol unit tests: 55 packet cases, all 128 single-bit nibble repairs, invalid-language/oversize/sequence rejection.
- JVM loopback integration: run via the protocol-test script; tests the production FrameIO and ItpPacket over an actual local TCP socket, including fragmentation and ACK. It does not test Android NSD, the Android connection manager lifecycle or phone audio.
- Web UI preview: navigation and dashboard checked in a browser; no JavaScript console errors observed.
- Physical-phone launch, discovery, offline STT/TTS, conversation, reconnection: pending until handsets are connected/tested. A successful build is not physical validation.
