import React, { useEffect, useState } from 'react';
import { Smartphone } from 'lucide-react';

interface HeroProps {
  onGetStarted: () => void;
  onOpenDevices?: () => void;
  connectedClientsCount?: number;
}

export const Hero: React.FC<HeroProps> = ({ onGetStarted, onOpenDevices, connectedClientsCount = 1 }) => {
  // Count-up animation state for stats footer
  const [stats, setStats] = useState({
    wireRate: 0,
    fidelity: 0,
    compression: 0,
    transports: 0
  });

  useEffect(() => {
    const duration = 1500;
    const startTime = performance.now();

    const updateStats = (currentTime: number) => {
      const elapsed = currentTime - startTime;
      const progress = Math.min(elapsed / duration, 1);
      const easeOut = 1 - Math.pow(1 - progress, 3);

      setStats({
        wireRate: Math.round(2000 * easeOut),
        fidelity: parseFloat((99.2 * easeOut).toFixed(1)),
        compression: Math.round(256 * easeOut),
        transports: Math.round(3 * easeOut)
      });
    };

    let animationFrameId: number;
    const animate = (currentTime: number) => {
      updateStats(currentTime);
      if (currentTime - startTime < duration) {
        animationFrameId = requestAnimationFrame(animate);
      } else {
        setStats({
          wireRate: 2000,
          fidelity: 99.2,
          compression: 256,
          transports: 3
        });
      }
    };

    animationFrameId = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(animationFrameId);
  }, []);

  return (
    <section id="hero" className="hero-section">
      <div className="hero-body">
        {/* Trust Row (Exact July Avatar Treatment with Indian Script Preview) */}
        <div className="trust-row">
          <div className="avatars">
            <div className="avatar-ring ring-1">
              <div className="avatar-inner">अ</div>
            </div>
            <div className="avatar-ring ring-2">
              <div className="avatar-inner">த</div>
            </div>
            <div className="avatar-ring ring-3">
              <div className="avatar-inner">14b</div>
            </div>
          </div>
          <div className="trust-pill">
            <span>11 Languages • Wi-Fi Direct • Wi-Fi LAN • Bluetooth</span>
          </div>
        </div>

        {/* Headline (Exact July Dot-Matrix Typography & Entrance Animation) */}
        <h1 className="headline">
          <span className="headline-line line-1">Meet SWAR</span>
        </h1>

        {/* Subhead */}
        <p className="subhead anim" style={{ '--d': '0.28s' } as React.CSSProperties}>
          SWAR is an offline voice-communication system transmitting compact text frames over local Wi-Fi Direct, Wi-Fi LAN, and Bluetooth Classic with local speech-to-text and synthetic voice playback.
        </p>

        {/* 3-Step Visual Quick Flow Guide */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          gap: '12px',
          maxWidth: '820px',
          margin: '20px auto 28px',
          width: '100%',
          textAlign: 'left'
        }}>
          <div style={{
            background: 'rgba(255, 255, 255, 0.03)',
            border: '1px solid rgba(255, 255, 255, 0.08)',
            borderRadius: '12px',
            padding: '14px 16px',
            display: 'flex',
            flexDirection: 'column',
            gap: '6px'
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#818cf8', fontSize: '13px', fontWeight: 700 }}>
              <span style={{ width: '22px', height: '22px', borderRadius: '50%', background: 'rgba(99, 102, 241, 0.2)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>1</span>
              <span>Speak Offline (STT)</span>
            </div>
            <p style={{ fontSize: '12px', color: '#94a3b8', lineHeight: 1.4 }}>
              Audio is captured and transcribed directly on your device across 11 supported languages with zero cloud dependency.
            </p>
          </div>

          <div style={{
            background: 'rgba(255, 255, 255, 0.03)',
            border: '1px solid rgba(255, 255, 255, 0.08)',
            borderRadius: '12px',
            padding: '14px 16px',
            display: 'flex',
            flexDirection: 'column',
            gap: '6px'
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#38bdf8', fontSize: '13px', fontWeight: 700 }}>
              <span style={{ width: '22px', height: '22px', borderRadius: '50%', background: 'rgba(56, 189, 248, 0.2)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>2</span>
              <span>Transmit Micro-Packets</span>
            </div>
            <p style={{ fontSize: '12px', color: '#94a3b8', lineHeight: 1.4 }}>
              Text frames are sent over Wi-Fi LAN, Direct P2P, or Bluetooth with 98% bandwidth reduction vs raw PCM.
            </p>
          </div>

          <div style={{
            background: 'rgba(255, 255, 255, 0.03)',
            border: '1px solid rgba(255, 255, 255, 0.08)',
            borderRadius: '12px',
            padding: '14px 16px',
            display: 'flex',
            flexDirection: 'column',
            gap: '6px'
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#34d399', fontSize: '13px', fontWeight: 700 }}>
              <span style={{ width: '22px', height: '22px', borderRadius: '50%', background: 'rgba(16, 185, 129, 0.2)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>3</span>
              <span>Synthesize & Speak (TTS)</span>
            </div>
            <p style={{ fontSize: '12px', color: '#94a3b8', lineHeight: 1.4 }}>
              The receiving device confirms ACK, synthesizes speech locally, and speaks the message out loud through the speakers.
            </p>
          </div>
        </div>

        {/* Glowing CTA Buttons -> Smooth Scroll to #studio and Connect Phone */}
        <div className="cta-wrapper anim-pulse" style={{ display: 'flex', gap: '14px', justifyContent: 'center', flexWrap: 'wrap', '--d': '0.4s' } as React.CSSProperties}>
          <button
            onClick={onGetStarted}
            className="cta-btn cursor-pointer"
          >
            Open Voice Studio
          </button>

          {onOpenDevices && (
            <button
              onClick={onOpenDevices}
              className="cta-btn cursor-pointer"
              style={{
                background: connectedClientsCount > 1 ? 'rgba(16, 185, 129, 0.15)' : 'rgba(255, 255, 255, 0.06)',
                color: connectedClientsCount > 1 ? '#34d399' : '#ffffff',
                border: `1px solid ${connectedClientsCount > 1 ? 'rgba(16, 185, 129, 0.4)' : 'rgba(255, 255, 255, 0.2)'}`,
                boxShadow: connectedClientsCount > 1 ? '0 0 16px rgba(16, 185, 129, 0.2)' : 'none',
                display: 'inline-flex',
                alignItems: 'center',
                gap: '8px'
              }}
            >
              <Smartphone size={16} />
              <span>
                {connectedClientsCount > 1
                  ? `Phone Connected (${connectedClientsCount} Devices Active)`
                  : 'Connect Phone (QR Code)'}
              </span>
            </button>
          )}

        </div>
      </div>

      {/* Stats Footer (4 Metrics matching SWAR communication data) */}
      <footer className="stats-footer">
        <div className="stat-card anim" style={{ '--d': '0.5s' } as React.CSSProperties}>
          <div className="stat-icon">&lt;</div>
          <div className="stat-value">{stats.wireRate}<span className="stat-suffix">bps</span></div>
          <div className="stat-label">Wire Rate SLA</div>
        </div>

        <div className="stat-card anim" style={{ '--d': '0.58s' } as React.CSSProperties}>
          <div className="stat-icon">%</div>
          <div className="stat-value">{stats.fidelity}<span className="stat-suffix">%</span></div>
          <div className="stat-label">Word Recovery</div>
        </div>

        <div className="stat-card anim" style={{ '--d': '0.66s' } as React.CSSProperties}>
          <div className="stat-icon">*</div>
          <div className="stat-value">{stats.compression}<span className="stat-suffix">×</span></div>
          <div className="stat-label">vs 256k Raw PCM</div>
        </div>

        <div className="stat-card anim" style={{ '--d': '0.74s' } as React.CSSProperties}>
          <div className="stat-icon">#</div>
          <div className="stat-value">{stats.transports}</div>
          <div className="stat-label">Local Transports</div>
        </div>
      </footer>
    </section>
  );
};
