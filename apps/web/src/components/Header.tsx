import React, { useState } from 'react';
import { History, Copy, Check, RotateCcw, Cpu, Radio, Download } from 'lucide-react';
import { DEFAULT_APK_DOWNLOAD_URL } from '../config';

interface HeaderProps {
  sessionId: string;
  hardwareStatus: string;
  isStreaming: boolean;
  activeSection: string;
  onNavigate: (section: string) => void;
  onOpenHistory: () => void;
  historyCount: number;
  onOpenDevices: () => void;
  peerCount: number;
  isConnected: boolean;
  activeTransport: string | null;
  connectedClientsCount?: number;
  wsConnectionStatus?: 'connected' | 'reconnecting' | 'offline';
  onReset: () => void;
  apkDownloadUrl?: string;
}

export const Header: React.FC<HeaderProps> = ({
  sessionId,
  hardwareStatus,
  isStreaming,
  activeSection,
  onNavigate,
  onOpenHistory,
  historyCount,
  onOpenDevices,
  peerCount,
  isConnected,
  activeTransport,
  connectedClientsCount = 1,
  wsConnectionStatus = 'connected',
  onReset,
  apkDownloadUrl = DEFAULT_APK_DOWNLOAD_URL,
}) => {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleCopySession = (e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(sessionId);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  const handleNavClick = (section: string) => {
    onNavigate(section);
    setMobileMenuOpen(false);
  };

  return (
    <div className="header-wrapper">
      <header className="header">
        {/* Logo Button (Exact July Circular Pill with Concentric Rings) */}
        <button
          onClick={() => handleNavClick('hero')}
          className="logo-btn"
          aria-label="SWAR Home"
          title="SWAR Offline Voice Communication"
        >
          <svg viewBox="0 0 100 100" style={{ width: '28px', height: '28px' }}>
            <circle cx="50" cy="50" r="46" fill="#ffffff" />
            <circle cx="50" cy="50" r="24" fill="none" stroke="#000000" strokeWidth="8" />
          </svg>
        </button>

        {/* Desktop Nav Pill (Exact July White Rounded-Full Container) */}
        <nav className="nav-pill" aria-label="Main Navigation">
          <button
            onClick={() => handleNavClick('hero')}
            className={`nav-link ${activeSection === 'hero' ? 'active' : ''}`}
          >
            Home
          </button>
          <button
            onClick={() => handleNavClick('studio')}
            className={`nav-link ${activeSection === 'studio' ? 'active' : ''}`}
          >
            Voice Studio
          </button>
          <button
            onClick={() => handleNavClick('receiver')}
            className={`nav-link ${activeSection === 'receiver' ? 'active' : ''}`}
          >
            Voice Receiver
          </button>
          <button
            onClick={() => {
              onOpenDevices();
              setMobileMenuOpen(false);
            }}
            className={`nav-link ${isConnected || connectedClientsCount > 1 ? 'active' : ''}`}
            style={{
              borderColor: isConnected || connectedClientsCount > 1 ? 'rgba(16, 185, 129, 0.4)' : undefined,
              color: isConnected || connectedClientsCount > 1 ? '#34d399' : undefined,
              display: 'inline-flex',
              alignItems: 'center',
              gap: '6px'
            }}
            title="Multi-Device Intercom & P2P Mesh"
          >
            <Radio size={13} color={isConnected || connectedClientsCount > 1 ? '#34d399' : undefined} />
            <span>
              {connectedClientsCount > 1
                ? `${connectedClientsCount} Devices Active`
                : isConnected
                ? (activeTransport || 'P2P').toUpperCase().replace('_', ' ')
                : 'P2P Mesh'}
            </span>
            <span
              style={{
                width: '7px',
                height: '7px',
                borderRadius: '50%',
                backgroundColor:
                  wsConnectionStatus === 'reconnecting'
                    ? '#fbbf24'
                    : isConnected || connectedClientsCount > 1
                    ? '#10b981'
                    : peerCount > 0
                    ? '#38bdf8'
                    : '#64748b',
                boxShadow: isConnected || connectedClientsCount > 1 ? '0 0 8px #10b981' : undefined
              }}
            />
          </button>

          <button
            onClick={() => {
              onOpenHistory();
              setMobileMenuOpen(false);
            }}
            className="nav-link"
            title="View transmission session history"
          >
            <History size={13} />
            <span>History</span>
            {historyCount > 0 && (
              <span
                style={{
                  width: '18px',
                  height: '18px',
                  fontSize: '10px',
                  backgroundColor: '#6366f1',
                  color: '#ffffff',
                  borderRadius: '50%',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontFamily: 'var(--font-mono)'
                }}
              >
                {historyCount}
              </span>
            )}
          </button>
        </nav>

        {/* Top-Right Action Group: Download APK & Session Status */}
        <div className="header-actions">
          {/* Download APK Button */}
          <a
            href={apkDownloadUrl}
            className="download-apk-btn"
            target={apkDownloadUrl.startsWith('http') ? '_blank' : undefined}
            rel={apkDownloadUrl.startsWith('http') ? 'noopener noreferrer' : undefined}
            download={!apkDownloadUrl.startsWith('http') ? 'swar-debug.apk' : undefined}
            title="Download SWAR Android Demo APK"
            aria-label="Download SWAR Android Demo APK"
          >
            <Download size={14} className="download-apk-icon" />
            <span className="download-apk-text">Download APK</span>
            <span className="download-apk-badge">v1.0</span>
          </a>

          {/* Status & Session Pill (Exact July Dark Pill Treatment) */}
          <div
            className="signin-pill"
            onClick={handleCopySession}
            title={`Click to copy session: ${sessionId} | Hardware: ${hardwareStatus}`}
          >
            <span className={`status-dot-live ${isStreaming ? 'transmitting' : ''}`} />
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', letterSpacing: '0.04em' }}>
              {isStreaming ? 'TRANSMITTING' : sessionId.slice(0, 8)}
            </span>
            {copied ? (
              <Check size={13} color="#10b981" />
            ) : (
              <Copy size={12} style={{ opacity: 0.6 }} />
            )}
            <button
              onClick={(e) => {
                e.stopPropagation();
                onReset();
              }}
              disabled={isStreaming}
              style={{
                background: 'none',
                border: 'none',
                color: 'inherit',
                cursor: isStreaming ? 'not-allowed' : 'pointer',
                display: 'flex',
                alignItems: 'center',
                marginLeft: '4px',
                opacity: 0.7
              }}
              title="Reset transceiver console"
            >
              <RotateCcw size={13} />
            </button>
          </div>
        </div>

        {/* Mobile Hamburger Button (Exact July Morphing Bar Button) */}
        <button
          onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
          className={`burger-btn ${mobileMenuOpen ? 'open' : ''}`}
          aria-label="Toggle Navigation Menu"
          aria-expanded={mobileMenuOpen}
        >
          <span className="burger-bar" />
          <span className="burger-bar" />
          <span className="burger-bar" />
        </button>
      </header>

      {/* Mobile Menu Sheet (Exact July Floating White Card) */}
      {mobileMenuOpen && (
        <>
          <div
            className="mobile-overlay"
            onClick={() => setMobileMenuOpen(false)}
          />
          <div className="mobile-menu" aria-label="Mobile Navigation Menu">
            <nav className="mobile-nav">
              <button
                onClick={() => handleNavClick('hero')}
                className={`mobile-nav-link ${activeSection === 'hero' ? 'active' : ''}`}
              >
                Home
              </button>
              <button
                onClick={() => handleNavClick('studio')}
                className={`mobile-nav-link ${activeSection === 'studio' ? 'active' : ''}`}
              >
                Voice Studio
              </button>
              <button
                onClick={() => handleNavClick('receiver')}
                className={`mobile-nav-link ${activeSection === 'receiver' ? 'active' : ''}`}
              >
                Voice Receiver
              </button>
              <button
                onClick={() => {
                  setMobileMenuOpen(false);
                  onOpenDevices();
                }}
                className="mobile-nav-link"
                style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', color: isConnected ? '#34d399' : undefined }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <Radio size={14} color={isConnected ? '#34d399' : undefined} />
                  <span>P2P Multi-Transport Mesh</span>
                </div>
                <span
                  style={{
                    width: '8px',
                    height: '8px',
                    borderRadius: '50%',
                    backgroundColor: isConnected ? '#10b981' : peerCount > 0 ? '#38bdf8' : '#64748b'
                  }}
                />
              </button>
              <button
                onClick={() => {
                  setMobileMenuOpen(false);
                  onOpenHistory();
                }}
                className="mobile-nav-link"
                style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%' }}
              >
                <span>Transmission History</span>
                {historyCount > 0 && (
                  <span
                    style={{
                      fontSize: '11px',
                      backgroundColor: '#6366f1',
                      color: '#ffffff',
                      borderRadius: '999px',
                      padding: '2px 8px',
                      fontFamily: 'var(--font-mono)'
                    }}
                  >
                    {historyCount}
                  </span>
                )}
              </button>
              <a
                href={apkDownloadUrl}
                className="mobile-apk-download-btn"
                target={apkDownloadUrl.startsWith('http') ? '_blank' : undefined}
                rel={apkDownloadUrl.startsWith('http') ? 'noopener noreferrer' : undefined}
                download={!apkDownloadUrl.startsWith('http') ? 'swar-debug.apk' : undefined}
                onClick={() => setMobileMenuOpen(false)}
                title="Download SWAR Android Demo APK"
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <Download size={15} color="#34d399" />
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', textAlign: 'left' }}>
                    <span style={{ fontWeight: 600, fontSize: '13.5px', color: '#ffffff' }}>Download Android APK</span>
                    <span style={{ fontSize: '10.5px', color: '#94a3b8' }}>Install demo on Android 12+</span>
                  </div>
                </div>
                <span className="mobile-apk-badge">APK</span>
              </a>
              <div
                className="mobile-signin-btn"
                onClick={handleCopySession}
              >
                <Cpu size={14} style={{ marginRight: '6px' }} />
                <span>SESSION: {sessionId.slice(0, 8)}</span>
              </div>
            </nav>
          </div>
        </>
      )}
    </div>
  );
};
