# Physical acoustic development results

These are development observations, not a range or reliability certification. Two phones were approximately 20 cm apart in a quiet room, as reported by the operator. The operator confirmed hearing modem tones from both phones. USB carried installation/control/diagnostics; application messages used speaker/microphone audio. A repeat with USB disconnected remains pending.

## Devices and successful text trial

| Condition | Value |
|---|---|
| Sender / responder | OnePlus CPH2487 / Google Pixel 9a |
| OS | Android 16 / Android 17 beta |
| Test installation | `in.itantra.mobile.acoustictest` |
| Distance / surroundings | Approximately 0.2 m / quiet room, operator reported |
| Media volume | OnePlus 80/160; Pixel 20/25 |
| Radios | Airplane mode enabled; Wi-Fi and Bluetooth explicitly disabled |
| PCM | 16,000 Hz, mono, signed 16-bit |
| Modulation | Audible BFSK, 2,000 and 3,000 Hz |
| Configured raw rate | 100 bit/s, 160 samples/symbol |
| FEC | Hamming(8,4), bit interleaving, bounded CRC-aided list decoding; preserved inner ITP FEC |
| Message fixtures | `A: help` (7 UTF-8 bytes), `B: heard` (8 UTF-8 bytes) |

Trial 6 completed both directions: the Pixel decoded `A: help` and the OnePlus decoded `B: heard`. Both senders observed a decoded ACK. There were two confirmed application deliveries out of two attempted messages in this single successful trial. This is not an estimate of general reliability; earlier development profiles failed.

The OnePlus metrics reported 38,163 ms message latency (enqueue through decode ACK), 10,234 ms ACK RTT, 30 corrected codewords, and no retransmissions in its last recorded snapshot. The snapshots can precede final counters, especially on the Pixel; they are not a synchronized end-of-test census. Reverse message latency was not captured. The message latency implies approximately 1.47 source-payload bit/s for the seven-byte forward message, including its ACK wait; this is distinct from both session goodput and the configured 100 bit/s modulation rate. Pixel TTS start/completion events were observed. Audible phrase intelligibility was not confirmed by the operator.

## Failed development trials

| Trial | Profile / change | Observed outcome |
|---|---|---|
| 1 | 500 raw bit/s | HELLO decoding, handshake did not complete |
| 2 | 500 bit/s; acquisition and turnaround changes | FEC failures; capability exchange failed |
| 3 | 250 bit/s; interleaving | Handshake did not complete |
| 4 | 250 bit/s; Hann matched filtering | One analyzed frame contained 11 bit errors; handshake failed |
| 5 | 250 bit/s; CRC-aided list decoding | One direction improved; analyzed reverse frame had 91 bit errors |
| 6 | 100 bit/s; timing-loop adjustment; Pixel volume 20/25 | Bidirectional text and decoded ACKs passed |

The first human-speech trial completed connection but the automatic recognition window expired without a final transcript. The second trial closed the supplied PCM writer after release and returned no final text, despite the operator confirming speech during Listening. Neither delivered a speech message.

A controlled probe then synthesized `Can you hear me?` using installed offline TTS on each phone and supplied its 24 kHz PCM directly to the on-device recognizer. Both produced correct partial hypotheses but an empty ordinary final callback. Enabling Android's supplied-audio segmented session produced completed segments `Can you hear me` on the OnePlus and `can you hear me` on the Pixel. This isolates a callback compatibility issue, not a microphone quality measurement. SpeechEngine now collects completed segments and finalizes them when the supplied stream closes; it never transmits partial hypotheses. The third human trial tests that integration separately.

Raw local debug logs are in the ignored `mobile/build/physical-tests/` directory. They are not committed. No microphone recording was saved. Text transcripts and modem-frame bytes in these logs are explicit test diagnostics.

## Integration provenance and limits

The mobile HTML/CSS, icons, and SWAR branding come from GitHub `origin/main` commit `8324091`. Acoustic Java, speech gating/model checks, backend relay additions, and validation scripts are local work. Existing web HTML/CSS edits were preserved. The Pixel's differently signed original app was preserved; the test package installs alongside it.

Pending: repeated trials at fixed conditions; 0.5/1/2/3/5 m tests; noise/orientation/obstruction trials; Hindi and Hinglish human speech; independently timed STT/TTS/end-to-end latency; measured model memory and accuracy on a labeled corpus; continuous conversation feedback tests; USB-disconnected confirmation; deployed HTTPS phone/browser interoperability. No kilometer range, kilobit acoustic payload rate, or broad reliability claim is supported.
