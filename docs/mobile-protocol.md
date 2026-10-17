# ITP/1 mobile demo draft

This is a new draft because the audit found no previous ITP implementation. It is not interoperable with the legacy JSON VoiceMessage protocol.

## Transport and CONTROL

Two phones use a persistent direct TCP stream over local LAN or one phone's hotspot. A host listens on 8988 and advertises `_swar._tcp.` through Android NSD. Manual private IPv4 entry is a discovery fallback. One peer is admitted at a time.

Each frame is a four-byte big-endian unsigned length, then a one-byte kind, then a payload. Length includes the kind, must be 2..32768, and is checked before allocation. Kind 1 is UTF-8 JSON CONTROL. Kind 2 is FEC-encoded ITP. Reads consume the full frame and handle TCP fragmentation/coalescing.

The client sends HELLO with protocol=1, random ephemeral UUID session, device model, six-digit host joining code, codec identifier, and requested language profiles. The host validates code/protocol/codec, then replies READY with its session and codec. Language profiles in HELLO are not a guarantee of installed recognition models; handset capabilities are shown on each device's dashboard. Data is rejected before handshake or for a mismatched session. REJECT closes invalid admission attempts.

PING/PONG every two seconds report a monotonic heartbeat RTT; lack of activity causes timeout. Client reconnect is bounded to four attempts with backoff. A host waits for another client. ACK contains the successfully decoded packet's sequence. It does not confirm TTS playback. Unacknowledged messages are marked uncertain after 12 seconds. TCP provides retransmission while connected; the application does not automatically resend unacknowledged speech after reconnect, avoiding accidental duplicate emergency messages. Resend explicitly if needed.

The joining code is only a basic admission check and travels over the local stream. Application traffic is not additionally encrypted. Use a trusted password-protected hotspot. CRC detects corruption, not malicious modification. No military, mesh or emergency reliability claims are made.

## ITP payload before FEC

All integer fields are big-endian.

| Field | Bytes | Meaning |
|---|---:|---|
| Magic/version | 4 | ASCII `ITP1` |
| Sender session | 16 | Random UUID, most-significant then least-significant 64 bits |
| Sequence | 8 | Positive increasing number per app instance |
| Language profile | 1 | 1 Hindi; 2 English; 3 experimental Hinglish |
| Codec | 1 | 0 UTF-8; 1 zlib DEFLATE of UTF-8 |
| Original UTF-8 length | 4 | 1..8192 |
| Encoded payload length | 4 | Exact remaining text payload length |
| Text payload | N | Lossless text bytes |
| CRC32 | 4 | IEEE CRC32 over all preceding bytes |

Header plus CRC is 42 bytes. DEFLATE is chosen only when smaller than the source; strict UTF-8 decoding rejects malformed input. Inflation is bounded to 8192 bytes. Text recovery is lossless relative to the STT output; recognition itself can be wrong.

Each raw byte is split high nibble first. Each nibble becomes an extended Hamming(8,4) codeword: parity at positions 1,2,4; data at 3,5,6,7; overall even parity at 8. Single-bit errors are corrected; detected double-bit errors are rejected. CRC32 runs after recovery and rejects most remaining corruption. FEC doubles raw ITP size. The total application frame for a payload of N bytes is `5 + 2*(42 + N)` bytes. FEC helps demonstrate corruption handling but is redundant with TCP's existing reliability in ordinary LAN operation; it does not recover a disconnected link.

The receiver retains a bounded 1024-entry deduplication set. TCP orders packets; nonduplicate non-increasing sequences within a connection are rejected. Receiver metadata is accepted only for the negotiated sender UUID.

## Metrics

The bitrate selector paces encoded text payload bytes at 500,1000,2000,4000,8000 or16000 bps between messages. A packet may be emitted as one TCP write; this is not physical RF shaping or Wi-Fi speed control.

Counters distinguish text payload bytes and framed application bytes (including CONTROL, ITP headers, CRC and FEC). Application throughput is bytes divided by app-session elapsed time. Kernel TCP/IP headers, retransmissions and physical radio overhead are not observable here. Heartbeat RTT and decoded ACK latency are not end-to-end acoustic latency. Radio packet loss, jitter and p95 acoustic latency remain unmeasured.
