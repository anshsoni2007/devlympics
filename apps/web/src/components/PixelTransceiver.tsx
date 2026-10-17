import React, { useState, useEffect, useRef } from 'react';
import { Mic, ArrowLeft, Volume2, VolumeX, Smartphone, Sparkles } from 'lucide-react';
import { sound } from '../utils/audioEffects';
import './PixelTransceiver.css';

export type ScreenMode = 'devices' | 'talking' | 'idle';

export interface PeerDevice {
  id: string;
  name: string;
  transport: 'WIFI-DIRECT' | 'BLE-MESH' | 'WI-FI-LAN';
  signalDbm: number;
  batteryPct: number;
  ip: string;
  status: 'ONLINE' | 'STANDBY';
}

const MOCK_PEERS: PeerDevice[] = [
  { id: 'dev-1', name: 'PIXEL-9A-FIELD', transport: 'WIFI-DIRECT', signalDbm: -42, batteryPct: 94, ip: '192.168.49.1', status: 'ONLINE' },
  { id: 'dev-2', name: 'MOTO-EDGE-TACTICAL', transport: 'BLE-MESH', signalDbm: -68, batteryPct: 81, ip: 'BLE:4A:2F:90', status: 'ONLINE' },
  { id: 'dev-3', name: 'ONEPLUS-BASE-01', transport: 'WI-FI-LAN', signalDbm: -55, batteryPct: 100, ip: '10.200.1.44', status: 'ONLINE' },
  { id: 'dev-4', name: 'COMMAND-RELAY-04', transport: 'WIFI-DIRECT', signalDbm: -74, batteryPct: 67, ip: '192.168.49.10', status: 'STANDBY' },
];

const TACTICAL_PHRASES = [
  'Route is clear, team advancing carefully.',
  'Urgent assistance needed at sector 4.',
  'Signal acquired. Radio check over.',
  'Holding position at north perimeter.',
  'All units switch to mesh channel 2.',
  'Medical dispatch en route to staging area.'
];

const LANGUAGES = [
  { code: 'EN_US', name: 'English (US)', speechCode: 'en-US' },
  { code: 'HI_IN', name: 'Hindi (भारत)', speechCode: 'hi-IN' },
  { code: 'HI_EN', name: 'Hinglish', speechCode: 'hi-IN' },
  { code: 'TA_IN', name: 'Tamil (தமிழ்)', speechCode: 'ta-IN' },
  { code: 'TE_IN', name: 'Telugu (తెలుగు)', speechCode: 'te-IN' },
];

interface PixelTransceiverProps {
  onBackToMain?: () => void;
  onNavigate?: (view: 'transceiver' | 'experience' | 'studio') => void;
}

export const PixelTransceiver: React.FC<PixelTransceiverProps> = ({ onBackToMain, onNavigate }) => {
  // Navigation & Screen Mode
  const [screenMode, setScreenMode] = useState<ScreenMode>('idle');
  const [connectedPeer, setConnectedPeer] = useState<PeerDevice | null>(MOCK_PEERS[0]);
  const [selectedDeviceIndex, setSelectedDeviceIndex] = useState<number>(0);
  const [isPairing, setIsPairing] = useState<boolean>(false);
  const [pairingLog, setPairingLog] = useState<string>('');

  // Audio & Talking
  const [isTalking, setIsTalking] = useState<boolean>(false);
  const [liveTranscript, setLiveTranscript] = useState<string>('READY WHEN YOU ARE.');
  const [isPeerSpeaking, setIsPeerSpeaking] = useState<boolean>(false);
  const [soundEnabled, setSoundEnabled] = useState<boolean>(true);
  const [languageIdx, setLanguageIdx] = useState<number>(0);
  const [eqLevels, setEqLevels] = useState<number[]>([12, 24, 18, 38, 48, 30, 20, 35, 42, 28, 16, 22]);

  // Audio animation canvas ref
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const animFrameRef = useRef<number | null>(null);
  const recognitionRef = useRef<any>(null);

  const currentLanguage = LANGUAGES[languageIdx];

  // Sound effects toggle
  const toggleSound = () => {
    const next = !soundEnabled;
    setSoundEnabled(next);
    sound.setEnabled(next);
    sound.playClick();
  };

  // Initialize SpeechRecognition if available in browser
  useEffect(() => {
    const SpeechRec = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (SpeechRec) {
      try {
        const rec = new SpeechRec();
        rec.continuous = true;
        rec.interimResults = true;
        rec.lang = currentLanguage.speechCode;

        rec.onresult = (event: any) => {
          let interim = '';
          for (let i = event.resultIndex; i < event.results.length; ++i) {
            interim += event.results[i][0].transcript;
          }
          if (interim.trim()) {
            setLiveTranscript(interim.toUpperCase());
          }
        };

        rec.onerror = (e: any) => {
          console.log('SpeechRecognition notice:', e.error);
        };

        rec.onend = () => {
          if (isTalking) {
            try { rec.start(); } catch (_) {}
          }
        };

        recognitionRef.current = rec;
      } catch (_) {}
    }

    return () => {
      if (recognitionRef.current) {
        try { recognitionRef.current.stop(); } catch (_) {}
      }
    };
  }, [currentLanguage]);

  // Screen 3: Phosphor Oscilloscope & Pixel Animation Canvas
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let t = 0;
    const render = () => {
      t += 0.04;
      const w = canvas.width;
      const h = canvas.height;

      ctx.clearRect(0, 0, w, h);

      // Draw subtle phosphor green matrix grid lines
      ctx.strokeStyle = 'rgba(195, 245, 59, 0.08)';
      ctx.lineWidth = 1;
      const gridSize = 16;
      for (let x = 0; x < w; x += gridSize) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, h);
        ctx.stroke();
      }
      for (let y = 0; y < h; y += gridSize) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(w, y);
        ctx.stroke();
      }

      // Draw harmonic Lissajous / breathing carrier wave
      ctx.lineWidth = 2;
      ctx.strokeStyle = '#c3f53b';
      ctx.shadowColor = 'rgba(195, 245, 59, 0.8)';
      ctx.shadowBlur = 6;

      ctx.beginPath();
      const centerY = h / 2;
      for (let x = 0; x < w; x += 2) {
        // Multi-frequency harmonic wave with subtle pixel stepping
        const wave1 = Math.sin(x * 0.05 + t) * 16;
        const wave2 = Math.cos(x * 0.02 - t * 0.7) * 8;
        const wave3 = Math.sin(x * 0.12 + t * 1.5) * 4;
        const y = centerY + wave1 + wave2 + wave3;

        // Quantize to 2px pixel grid for authentic retro CRT feel
        const pxY = Math.round(y / 2) * 2;

        if (x === 0) ctx.moveTo(x, pxY);
        else ctx.lineTo(x, pxY);
      }
      ctx.stroke();

      // Secondary ghost wave
      ctx.lineWidth = 1;
      ctx.strokeStyle = 'rgba(195, 245, 59, 0.35)';
      ctx.shadowBlur = 0;
      ctx.beginPath();
      for (let x = 0; x < w; x += 3) {
        const y = centerY + Math.sin(x * 0.04 - t * 1.2) * 22;
        const pxY = Math.round(y / 2) * 2;
        if (x === 0) ctx.moveTo(x, pxY);
        else ctx.lineTo(x, pxY);
      }
      ctx.stroke();

      // Telemetry heartbeat pulses at the ends
      const pulseX = (Math.sin(t * 1.8) * 0.5 + 0.5) * w;
      ctx.fillStyle = '#c3f53b';
      ctx.fillRect(Math.round(pulseX / 4) * 4, centerY - 2, 4, 4);

      animFrameRef.current = requestAnimationFrame(render);
    };

    render();

    return () => {
      if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current);
    };
  }, [screenMode]);

  // Audio Equalizer bars animation when talking
  useEffect(() => {
    if (!isTalking && !isPeerSpeaking) return;
    const interval = setInterval(() => {
      setEqLevels(prev =>
        prev.map(() => Math.floor(Math.random() * 42) + 8)
      );
    }, 90);
    return () => clearInterval(interval);
  }, [isTalking, isPeerSpeaking]);

  // Handle Starting and Stopping Push-To-Talk
  const handleToggleTalk = () => {
    sound.playClick();
    if (!connectedPeer) {
      // If not connected, switch to device screen first
      setScreenMode('devices');
      return;
    }

    if (isTalking) {
      // Stop Talking -> return to Mode 3 (Connected & Idle)
      stopTalking();
    } else {
      // Start Talking -> Mode 2 (Connected & Talking)
      startTalking();
    }
  };

  const startTalking = () => {
    sound.playPttStart();
    setIsTalking(true);
    setScreenMode('talking');
    setLiveTranscript('LISTENING... SPEAK NOW█');

    if (recognitionRef.current) {
      try {
        recognitionRef.current.lang = currentLanguage.speechCode;
        recognitionRef.current.start();
      } catch (_) {}
    }
  };

  const stopTalking = () => {
    sound.playPttEnd();
    setIsTalking(false);

    if (recognitionRef.current) {
      try { recognitionRef.current.stop(); } catch (_) {}
    }

    // Switch to Screen 3 (Connected and Not Talking Animation) after transmission completes
    setTimeout(() => {
      setScreenMode('idle');
      // Simulate peer response after 2.5s to show full transceiver loop
      simulatePeerResponse();
    }, 1800);
  };

  // Simulate peer receiving message & responding
  const simulatePeerResponse = () => {
    if (!connectedPeer) return;
    setTimeout(() => {
      setIsPeerSpeaking(true);
      setScreenMode('talking');
      const responseText = `ROGER ${connectedPeer.name}: COPY THAT. LINK IS STABLE ON CHANNEL 1.`;
      setLiveTranscript(responseText);

      // Play native SpeechSynthesis TTS
      if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
        const u = new SpeechSynthesisUtterance(responseText);
        u.rate = 1.05;
        u.pitch = 0.95;
        u.onend = () => {
          setIsPeerSpeaking(false);
          setScreenMode('idle');
        };
        window.speechSynthesis.speak(u);
      } else {
        setTimeout(() => {
          setIsPeerSpeaking(false);
          setScreenMode('idle');
        }, 3000);
      }
    }, 2200);
  };

  // Quick Dispatch phrase send
  const handleSendPhrase = (phrase: string) => {
    sound.playPttStart();
    setIsTalking(true);
    setScreenMode('talking');
    setLiveTranscript(phrase.toUpperCase());

    setTimeout(() => {
      stopTalking();
    }, 1200);
  };

  // Connect to chosen peer
  const handleConnectDevice = (peer: PeerDevice) => {
    sound.playClick();
    setIsPairing(true);
    setPairingLog(`PAIRING WITH ${peer.name}...`);

    setTimeout(() => {
      setPairingLog('X25519 KEY EXCHANGE: OK');
    }, 600);

    setTimeout(() => {
      setPairingLog(`LINK READY: ${peer.transport}`);
      sound.playConnectChirp();
    }, 1200);

    setTimeout(() => {
      setIsPairing(false);
      setConnectedPeer(peer);
      setScreenMode('idle'); // Mode 3: Connected & Not Talking
      setLiveTranscript('READY WHEN YOU ARE.');
    }, 1800);
  };

  // Click Wheel Button Handlers
  const handleWheelDev = () => {
    sound.playClick();
    setScreenMode(screenMode === 'devices' ? 'idle' : 'devices');
  };

  const handleWheelTxt = () => {
    sound.playClick();
    // Cycle through sample tactical phrases
    const randomPhrase = TACTICAL_PHRASES[Math.floor(Math.random() * TACTICAL_PHRASES.length)];
    handleSendPhrase(randomPhrase);
  };

  const handleWheelLanguage = () => {
    sound.playClick();
    setLanguageIdx((languageIdx + 1) % LANGUAGES.length);
  };

  return (
    <div className="transceiver-page-wrapper">
      {/* Top Bar Header */}
      <div className="transceiver-topbar">
        <div className="transceiver-topbar-brand">
          <span>SWAR</span>
          <span style={{ fontSize: '14px', opacity: 0.7 }}>TRANSCVR//01</span>
        </div>

        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          {onNavigate && (
            <>
              <button
                onClick={() => onNavigate('experience')}
                className="transceiver-nav-pill"
                title="Switch to SWAR Web Presentation"
              >
                <span>WEB EXPERIENCE</span>
              </button>
              <button
                onClick={() => onNavigate('studio')}
                className="transceiver-nav-pill"
                title="Switch to Audio Studio Dashboard"
              >
                <span>VOICE STUDIO</span>
              </button>
            </>
          )}

          <button
            onClick={toggleSound}
            className="transceiver-nav-pill"
            title="Toggle procedural audio sound effects"
          >
            {soundEnabled ? <Volume2 size={13} /> : <VolumeX size={13} />}
            <span>{soundEnabled ? 'FX ON' : 'FX OFF'}</span>
          </button>

          {onBackToMain && (
            <button
              onClick={onBackToMain}
              className="transceiver-nav-pill"
              title="Return to main presentation"
            >
              <ArrowLeft size={13} />
              <span>EXIT</span>
            </button>
          )}
        </div>
      </div>

      {/* Screen Mode Quick Switcher Bar for Review & Testing */}
      <div style={{ display: 'flex', gap: '6px', marginBottom: '14px', flexWrap: 'wrap', justifyContent: 'center' }}>
        <button
          onClick={() => { sound.playClick(); setScreenMode('devices'); }}
          className="transceiver-nav-pill"
          style={{
            borderColor: screenMode === 'devices' ? 'var(--swar-phosphor)' : undefined,
            color: screenMode === 'devices' ? 'var(--swar-phosphor)' : undefined
          }}
        >
          <Smartphone size={12} />
          <span>1. AVAILABLE DEVICES</span>
        </button>

        <button
          onClick={() => { sound.playClick(); setScreenMode('talking'); setIsTalking(true); setLiveTranscript('EMERGENCY RESPONSE UNIT DISPATCHED TO NORTH GATE.█'); }}
          className="transceiver-nav-pill"
          style={{
            borderColor: screenMode === 'talking' ? 'var(--swar-phosphor)' : undefined,
            color: screenMode === 'talking' ? 'var(--swar-phosphor)' : undefined
          }}
        >
          <Mic size={12} />
          <span>2. TALKING (STT TEXT)</span>
        </button>

        <button
          onClick={() => { sound.playClick(); setScreenMode('idle'); setIsTalking(false); setLiveTranscript('READY WHEN YOU ARE.'); }}
          className="transceiver-nav-pill"
          style={{
            borderColor: screenMode === 'idle' ? 'var(--swar-phosphor)' : undefined,
            color: screenMode === 'idle' ? 'var(--swar-phosphor)' : undefined
          }}
        >
          <Sparkles size={12} />
          <span>3. CONNECTED IDLE (ANIMATION)</span>
        </button>
      </div>

      {/* ====================================================================
          THE PHYSICAL HARDWARE DEVICE CHASSIS (Matches Reference Image)
          ==================================================================== */}
      <div className="handheld-chassis">
        {/* Top Speaker Cutout / Grill Slot */}
        <div className="chassis-speaker-slit" />

        {/* The Retro Pixelated CRT Bezel */}
        <div className="screen-bezel">
          <div className="pixel-screen">
            {/* Screen Header */}
            <div className="screen-header">
              <div className="screen-header-brand">
                <div className="screen-brand-icon-box">V</div>
                <span className="screen-brand-text">SWAR</span>
              </div>

              <div className="screen-header-status">
                {screenMode === 'devices' ? (
                  <>
                    <span className="status-dot-blink" />
                    <span>RADAR SCAN</span>
                  </>
                ) : screenMode === 'talking' ? (
                  <>
                    <span className="status-dot-blink" style={{ backgroundColor: '#ef4444', boxShadow: '0 0 6px #ef4444' }} />
                    <span style={{ color: isPeerSpeaking ? '#38bdf8' : '#ef4444' }}>
                      {isPeerSpeaking ? '● RX PEER' : '● TX LIVE'}
                    </span>
                  </>
                ) : (
                  <>
                    <span className="status-dot-blink" />
                    <span>■ STANDBY</span>
                  </>
                )}
              </div>
            </div>

            {/* Dotted Baseline (Exact match from photo) */}
            <div className="screen-dotted-bar">
              ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪
            </div>

            {/* Status Box */}
            <div className="screen-status-badge">
              {screenMode === 'devices'
                ? isPairing ? pairingLog : 'SELECT NEARBY PEER TO CONNECT'
                : screenMode === 'talking'
                ? isPeerSpeaking ? `INCOMING AUDIO FROM ${connectedPeer?.name}` : 'VOICE ACTIVITY DETECTED • 1.8 KBPS'
                : 'NO VOICE ACTIVITY'}
            </div>

            {/* ==========================================================
                SCREEN 1: AVAILABLE DEVICES TO CONNECT
                ========================================================== */}
            {screenMode === 'devices' && (
              <div className="screen-device-list-container">
                {MOCK_PEERS.map((peer, idx) => {
                  const isSelected = selectedDeviceIndex === idx;
                  const isConnected = connectedPeer?.id === peer.id;
                  return (
                    <div
                      key={peer.id}
                      onClick={() => {
                        setSelectedDeviceIndex(idx);
                        handleConnectDevice(peer);
                      }}
                      className={`screen-device-item ${isSelected ? 'active' : ''}`}
                    >
                      <div className="device-left-meta">
                        <span className="device-name-text">
                          {isSelected ? '► ' : '  '}
                          {peer.name}
                        </span>
                        <span className="device-transport-tag">
                          [{peer.transport}] • {peer.ip}
                        </span>
                      </div>

                      <div className="device-right-action">
                        <span className="device-signal-bars">
                          {peer.signalDbm > -50 ? '■■■■■' : peer.signalDbm > -65 ? '■■■■□' : '■■■□□'}
                        </span>
                        <span className="device-action-hint">
                          {isConnected ? 'CONNECTED' : 'CONNECT'}
                        </span>
                      </div>
                    </div>
                  );
                })}

                <div style={{ fontSize: '11px', textAlign: 'center', opacity: 0.65, marginTop: 'auto', padding: '4px' }}>
                  AUTOMATIC NSD & BLE BEACONS BROADCASTING
                </div>
              </div>
            )}

            {/* ==========================================================
                SCREEN 2: CONNECTED & TALKING (LIVE STT TEXT & AUDIO VISUALIZER)
                ========================================================== */}
            {screenMode === 'talking' && (
              <>
                {/* Dynamic Live Audio Equalizer Bars */}
                <div className="live-eq-bars-container">
                  {eqLevels.map((lvl, i) => (
                    <div
                      key={i}
                      className="eq-bar"
                      style={{ height: `${lvl}px` }}
                    />
                  ))}
                </div>

                {/* Main Live Transcript Box */}
                <div className="screen-transcript-box">
                  <div>
                    <div className="transcript-label-mini">
                      LIVE TRANSCRIPT (STT) • {isPeerSpeaking ? 'REMOTE RECEIVE' : 'LOCAL TRANSMIT'}
                    </div>
                    <div className="transcript-body-text">
                      {liveTranscript}
                      <span className="cursor-block" />
                    </div>
                  </div>

                  <div className="transcript-telemetry-meta">
                    <span>WIRE: 1,850 BPS</span>
                    <span>FEC: HAMMING(8,4) OK</span>
                    <span>CRC: VERIFIED</span>
                  </div>
                </div>
              </>
            )}

            {/* ==========================================================
                SCREEN 3: CONNECTED & NOT TALKING (RETRO PIXEL ANIMATION)
                ========================================================== */}
            {screenMode === 'idle' && (
              <>
                {/* Phosphor Oscilloscope Sine Waves Pixel Canvas */}
                <div className="screen-animation-viewport">
                  <canvas
                    ref={canvasRef}
                    width={320}
                    height={90}
                    className="pixel-oscilloscope-canvas"
                  />
                </div>

                {/* Main Transcript Display Box (Exact Match to Image) */}
                <div className="screen-transcript-box">
                  <div className="transcript-label-mini">
                    LIVE TRANSCRIPT
                  </div>
                  <div className="transcript-body-text">
                    READY WHEN YOU ARE.
                    <span className="cursor-block" />
                  </div>
                </div>
              </>
            )}

            {/* Screen Bottom Telemetry Footer */}
            <div className="screen-footer-bar">
              <span>{currentLanguage.code}</span>
              <span>{connectedPeer ? connectedPeer.name : 'NO PEER'}</span>
            </div>
          </div>
        </div>

        {/* ====================================================================
            THE PHYSICAL CLICK WHEEL & CENTER BUTTON (Matches Reference Image)
            ==================================================================== */}
        <div className="click-wheel-container">
          {/* Top Label: PUSH TO TALK */}
          <button
            onClick={handleToggleTalk}
            className="wheel-label-btn wheel-label-top"
            title="Push to talk / Start transmission"
          >
            PUSH TO TALK
          </button>

          {/* Left Label: DEV (Available Devices) */}
          <button
            onClick={handleWheelDev}
            className="wheel-label-btn wheel-label-left"
            title="View available devices to connect"
          >
            DEV
          </button>

          {/* Right Label: TXT (Quick Tactical Phrases) */}
          <button
            onClick={handleWheelTxt}
            className="wheel-label-btn wheel-label-right"
            title="Dispatch quick tactical phrase"
          >
            TXT
          </button>

          {/* Bottom Label: ... (Change Language / Dialect) */}
          <button
            onClick={handleWheelLanguage}
            className="wheel-label-btn wheel-label-bottom"
            title={`Switch language dialect (Current: ${currentLanguage.name})`}
          >
            ...
          </button>

          {/* Center Circular Tactile Button */}
          <button
            onClick={handleToggleTalk}
            className={`wheel-center-btn ${isTalking ? 'is-active' : ''}`}
            title="Tap to start / stop speaking"
            aria-label="Push To Talk Microphone"
          >
            <Mic size={24} className="center-btn-mic" />
            <span className="center-btn-text">
              {isTalking ? 'LIVE' : 'TALK'}
            </span>
          </button>
        </div>

        {/* Chassis Bottom Caption (Matches reference image) */}
        <div className="chassis-bottom-caption">
          TAP CENTER TO START / STOP
        </div>
      </div>

      {/* Quick Tactical Dispatch Buttons */}
      <div style={{ marginTop: '16px', textAlign: 'center' }}>
        <div style={{ fontSize: '11px', color: '#94a3b8', marginBottom: '6px', letterSpacing: '0.06em', textTransform: 'uppercase' }}>
          Quick Test Dispatch
        </div>
        <div className="tactical-phrases-bar">
          {TACTICAL_PHRASES.map((phrase, i) => (
            <button
              key={i}
              onClick={() => handleSendPhrase(phrase)}
              className="tactical-pill"
            >
              "{phrase}"
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};
