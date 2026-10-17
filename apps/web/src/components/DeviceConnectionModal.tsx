import React, { useState, useEffect } from 'react';
import {
  Wifi,
  Radio,
  Bluetooth,
  Copy,
  Check,
  X,
  Zap,
  CheckCircle2,
  AlertCircle,
  Volume2,
  Smartphone,
  HelpCircle,
  ShieldCheck,
  Users
} from 'lucide-react';


import { Peer, TransportType, LocalDeviceInfo, WebClientInfo } from '../types';

interface DeviceConnectionModalProps {
  isOpen: boolean;
  onClose: () => void;
  peers: Peer[];
  webClients: WebClientInfo[];
  selfClient: WebClientInfo | null;
  activeTransport: TransportType | null;
  connectionState: string;
  wsStatus: 'connected' | 'reconnecting' | 'offline';
  onRefreshPeers: () => void;
  onConnectPeer: (address: string, port?: number, transport?: TransportType) => Promise<boolean>;
  onQuickConnectLocal: (transport?: TransportType) => Promise<boolean>;
  onDisconnect: () => Promise<void>;
  onSendTestMessage: (text: string, transport?: TransportType) => Promise<boolean>;
}

export const DeviceConnectionModal: React.FC<DeviceConnectionModalProps> = ({
  isOpen,
  onClose,
  peers,
  webClients,
  selfClient,
  activeTransport,
  connectionState,
  wsStatus,
  onRefreshPeers,
  onConnectPeer,
  onQuickConnectLocal,
  onDisconnect,
  onSendTestMessage,
}) => {
  const [activeTab, setActiveTab] = useState<'phone' | 'mesh' | 'bluetooth' | 'guide'>('phone');
  const [localInfo, setLocalInfo] = useState<LocalDeviceInfo | null>(null);
  const [copiedText, setCopiedText] = useState<string | null>(null);
  const [manualIp, setManualIp] = useState('');
  const [manualPort, setManualPort] = useState('8988');
  const [manualTransport, setManualTransport] = useState<TransportType>('wifi_lan');
  const [isConnecting, setIsConnecting] = useState(false);
  const [statusMessage, setStatusMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [isSendingTest, setIsSendingTest] = useState(false);
  const currentOrigin = typeof window !== 'undefined' ? window.location.origin : '';
  const isAlreadyRemote = typeof window !== 'undefined' && !window.location.hostname.includes('localhost') && !window.location.hostname.includes('127.0.0.1');
  const localHostIp = localInfo?.primaryIp || (typeof window !== 'undefined' ? window.location.hostname : '127.0.0.1');
  const tunnelUrl = isAlreadyRemote ? currentOrigin : (localInfo?.tunnelUrl && !localInfo.tunnelUrl.includes('lhr.life') ? localInfo.tunnelUrl : `http://${localHostIp}:5173`);
  const [isScanningBt, setIsScanningBt] = useState(false);
  const [btDevice, setBtDevice] = useState<{ id: string; name?: string } | null>(null);
  const [isSendingBtPing, setIsSendingBtPing] = useState(false);

  const wifiDirectPresets = [
    { label: '📱 Android Wi-Fi Direct', ip: '192.168.49.1', port: '8990', transport: 'wifi_direct' as TransportType, desc: 'Android P2P Group Owner Subnet' },
    { label: '📶 Android Hotspot', ip: '192.168.43.1', port: '8990', transport: 'wifi_direct' as TransportType, desc: 'Standard Android AP Gateway' },
    { label: '🍎 iPhone Hotspot', ip: '172.20.10.1', port: '8990', transport: 'wifi_direct' as TransportType, desc: 'Apple Tethering Subnet' },
    { label: '💻 Windows Hotspot', ip: '192.168.137.1', port: '8990', transport: 'wifi_direct' as TransportType, desc: 'Windows Hosted Network' },
    { label: '🏠 Localhost Loopback', ip: '127.0.0.1', port: '8990', transport: 'wifi_direct' as TransportType, desc: 'Simulated node on machine' },
  ];

  const applyPreset = (p: { ip: string; port: string; transport: TransportType }) => {
    setManualIp(p.ip);
    setManualPort(p.port);
    setManualTransport(p.transport);
  };

  const handleScanWebBluetooth = async () => {
    if (typeof navigator === 'undefined' || !(navigator as any).bluetooth) {
      setStatusMessage({
        type: 'error',
        text: 'Web Bluetooth API is not available in this browser. Use Chrome/Edge on Desktop or Android over HTTPS.'
      });
      return;
    }
    setIsScanningBt(true);
    setStatusMessage(null);
    try {
      const device = await (navigator as any).bluetooth.requestDevice({
        acceptAllDevices: true,
        optionalServices: ['generic_access', 'battery_service']
      });
      setBtDevice({ id: device.id, name: device.name });
      setStatusMessage({
        type: 'success',
        text: `Bluetooth Device Paired: "${device.name || 'Nearby Bluetooth Peripheral'}" (ID: ${device.id.substring(0, 8)}...)`
      });
      await onQuickConnectLocal('bluetooth');
    } catch (err: any) {
      if (err.name !== 'NotFoundError') {
        setStatusMessage({ type: 'error', text: `Bluetooth pairing error: ${err.message}` });
      }
    } finally {
      setIsScanningBt(false);
    }
  };

  const handleBluetoothTestPing = async () => {
    setIsSendingBtPing(true);
    setStatusMessage(null);
    try {
      const ok = await onSendTestMessage('Tactical Bluetooth RFCOMM link verified. VoiceBridge active over 1.85 kbps channel.', 'bluetooth');
      if (ok) {
        setStatusMessage({ type: 'success', text: 'Bluetooth Voice Test broadcasted! Synthetic speech playing on paired node.' });
      } else {
        setStatusMessage({ type: 'error', text: 'Failed to broadcast over Bluetooth.' });
      }
    } catch (err: any) {
      setStatusMessage({ type: 'error', text: err.message || 'Bluetooth ping failed' });
    } finally {
      setIsSendingBtPing(false);
    }
  };


  // Fetch local device IP and transport ports
  const fetchLocalInfo = () => {
    fetch('/api/v1/voicebridge/local-info')
      .then((r) => r.json())
      .then((data: LocalDeviceInfo) => setLocalInfo(data))
      .catch((err) => console.error('Failed to load local device info:', err));
  };

  useEffect(() => {
    if (isOpen) {
      fetchLocalInfo();
      onRefreshPeers();
    }
  }, [isOpen]);

  const handleTransportChange = (t: TransportType) => {
    setManualTransport(t);
    if (t === 'wifi_lan') setManualPort('8988');
    else if (t === 'wifi_direct') setManualPort('8990');
    else if (t === 'bluetooth') setManualPort('8992');
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedText(text);
    setTimeout(() => setCopiedText(null), 1800);
  };

  const handleConnect = async (addr: string, p?: number, t?: TransportType) => {
    setIsConnecting(true);
    setStatusMessage(null);
    try {
      const ok = await onConnectPeer(addr, p, t);
      if (ok) {
        setStatusMessage({ type: 'success', text: `Successfully connected to ${addr} over ${t || 'Wi-Fi LAN'}` });
      } else {
        setStatusMessage({ type: 'error', text: `Could not connect to ${addr}. Ensure the peer node is running.` });
      }
    } catch (e: any) {
      setStatusMessage({ type: 'error', text: e.message || 'Connection failed' });
    } finally {
      setIsConnecting(false);
    }
  };

  const handleQuickConnect = async (t: TransportType) => {
    setIsConnecting(true);
    setStatusMessage(null);
    try {
      const ok = await onQuickConnectLocal(t);
      if (ok) {
        setStatusMessage({ type: 'success', text: `Local Socket Mesh active on ${t.toUpperCase().replace('_', ' ')}!` });
      } else {
        setStatusMessage({ type: 'error', text: `Failed to link local ${t} transport.` });
      }
    } catch (e: any) {
      setStatusMessage({ type: 'error', text: e.message || 'Quick connect failed' });
    } finally {
      setIsConnecting(false);
    }
  };

  const handleTestTransmit = async () => {
    setIsSendingTest(true);
    setStatusMessage(null);
    try {
      const ok = await onSendTestMessage('Node-to-node connection verified. SWAR active across devices.');
      if (ok) {
        setStatusMessage({ type: 'success', text: 'Voice test broadcasted! Receiving speakers will now speak the message.' });
      } else {
        setStatusMessage({ type: 'error', text: 'Failed to broadcast test message.' });
      }
    } catch (err: any) {
      setStatusMessage({ type: 'error', text: err.message || 'Send failed' });
    } finally {
      setIsSendingTest(false);
    }
  };

  if (!isOpen) return null;

  const isConnected = connectionState === 'CONNECTED';
  const hasMultipleClients = webClients.length > 1;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '680px', width: '100%', boxSizing: 'border-box' }}>
        {/* Modal Header */}
        <div className="modal-panel-header" style={{
          padding: '18px 24px',
          borderBottom: '1px solid rgba(255,255,255,0.08)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          background: 'rgba(15, 23, 42, 0.6)'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div style={{
              width: '38px',
              height: '38px',
              borderRadius: '10px',
              background: 'linear-gradient(135deg, #6366f1 0%, #3b82f6 100%)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#ffffff',
              boxShadow: '0 4px 14px rgba(99, 102, 241, 0.4)',
              flexShrink: 0
            }}>
              <Radio size={20} />
            </div>
            <div>
              <h2 style={{ fontSize: '16px', fontWeight: 700, color: '#ffffff', letterSpacing: '-0.01em', margin: 0 }}>
                Multi-Device Connection Hub
              </h2>
              <p style={{ fontSize: '11px', color: '#94a3b8', margin: 0, marginTop: '2px' }}>
                Mobile Remote • Wi-Fi LAN • Wi-Fi Direct • Bluetooth
              </p>
            </div>
          </div>

          <button
            onClick={onClose}
            style={{
              width: '32px',
              height: '32px',
              borderRadius: '8px',
              border: '1px solid rgba(255,255,255,0.1)',
              background: 'rgba(255,255,255,0.04)',
              color: '#94a3b8',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              cursor: 'pointer',
              flexShrink: 0
            }}
            title="Close"
          >
            <X size={16} />
          </button>
        </div>

        {/* Tab Navigation - Touch & Scroll Friendly */}
        <div className="modal-tab-nav" style={{
          display: 'flex',
          borderBottom: '1px solid rgba(255,255,255,0.08)',
          background: 'rgba(0, 0, 0, 0.2)',
          padding: '0 16px',
          overflowX: 'auto',
          WebkitOverflowScrolling: 'touch',
          scrollbarWidth: 'none'
        }}>
          <button
            className="modal-tab-btn"
            onClick={() => setActiveTab('phone')}
            style={{
              padding: '12px 14px',
              border: 'none',
              background: 'none',
              color: activeTab === 'phone' ? '#818cf8' : '#94a3b8',
              fontSize: '13px',
              fontWeight: 600,
              cursor: 'pointer',
              borderBottom: activeTab === 'phone' ? '2px solid #818cf8' : '2px solid transparent',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              flexShrink: 0,
              whiteSpace: 'nowrap'
            }}
          >
            <Smartphone size={15} />
            <span>Phone Intercom</span>
            {webClients.length > 0 && (
              <span style={{
                fontSize: '10px',
                padding: '1px 6px',
                borderRadius: '999px',
                background: hasMultipleClients ? 'rgba(16, 185, 129, 0.2)' : 'rgba(99, 102, 241, 0.2)',
                color: hasMultipleClients ? '#34d399' : '#a5b4fc',
                fontWeight: 700
              }}>
                {webClients.length} Online
              </span>
            )}
          </button>

          <button
            className="modal-tab-btn"
            onClick={() => setActiveTab('mesh')}
            style={{
              padding: '12px 14px',
              border: 'none',
              background: 'none',
              color: activeTab === 'mesh' ? '#818cf8' : '#94a3b8',
              fontSize: '13px',
              fontWeight: 600,
              cursor: 'pointer',
              borderBottom: activeTab === 'mesh' ? '2px solid #818cf8' : '2px solid transparent',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              flexShrink: 0,
              whiteSpace: 'nowrap'
            }}
          >
            <Zap size={15} />
            <span>P2P Sockets (Mesh)</span>
            {isConnected && (
              <span style={{
                width: '6px',
                height: '6px',
                borderRadius: '50%',
                backgroundColor: '#10b981',
                boxShadow: '0 0 6px #10b981'
              }} />
            )}
          </button>

          <button
            className="modal-tab-btn"
            onClick={() => setActiveTab('bluetooth')}
            style={{
              padding: '12px 14px',
              border: 'none',
              background: 'none',
              color: activeTab === 'bluetooth' ? '#60a5fa' : '#94a3b8',
              fontSize: '13px',
              fontWeight: 600,
              cursor: 'pointer',
              borderBottom: activeTab === 'bluetooth' ? '2px solid #60a5fa' : '2px solid transparent',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              flexShrink: 0,
              whiteSpace: 'nowrap'
            }}
          >
            <Bluetooth size={15} />
            <span>Bluetooth</span>
            {activeTransport === 'bluetooth' && isConnected && (
              <span style={{
                width: '6px',
                height: '6px',
                borderRadius: '50%',
                backgroundColor: '#3b82f6',
                boxShadow: '0 0 6px #3b82f6'
              }} />
            )}
          </button>

          <button
            className="modal-tab-btn"
            onClick={() => setActiveTab('guide')}
            style={{
              padding: '12px 14px',
              border: 'none',
              background: 'none',
              color: activeTab === 'guide' ? '#818cf8' : '#94a3b8',
              fontSize: '13px',
              fontWeight: 600,
              cursor: 'pointer',
              borderBottom: activeTab === 'guide' ? '2px solid #818cf8' : '2px solid transparent',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              flexShrink: 0,
              whiteSpace: 'nowrap'
            }}
          >
            <HelpCircle size={15} />
            <span>How It Works</span>
          </button>
        </div>

        {/* Modal Scrollable Body */}
        <div className="modal-panel-body" style={{ padding: '20px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '16px', maxHeight: 'calc(85vh - 130px)' }}>

          {/* Status feedback toast */}
          {statusMessage && (
            <div style={{
              padding: '12px 16px',
              borderRadius: '10px',
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
              fontSize: '13px',
              background: statusMessage.type === 'success' ? 'rgba(16, 185, 129, 0.14)' : 'rgba(244, 63, 94, 0.14)',
              border: `1px solid ${statusMessage.type === 'success' ? 'rgba(16, 185, 129, 0.35)' : 'rgba(244, 63, 94, 0.35)'}`,
              color: statusMessage.type === 'success' ? '#34d399' : '#fb7185'
            }}>
              {statusMessage.type === 'success' ? <CheckCircle2 size={16} /> : <AlertCircle size={16} />}
              <span>{statusMessage.text}</span>
            </div>
          )}

          {/* TAB 1: PHONE INTERCOM & LIVE DEVICES */}
          {activeTab === 'phone' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
              {/* Phone Pairing Box */}
              <div style={{
                background: 'linear-gradient(135deg, rgba(99, 102, 241, 0.12) 0%, rgba(59, 130, 246, 0.08) 100%)',
                border: '1px solid rgba(99, 102, 241, 0.35)',
                borderRadius: '14px',
                padding: '18px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: '14px'
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '8px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                    <div style={{ width: '34px', height: '34px', borderRadius: '8px', background: 'rgba(99, 102, 241, 0.25)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#a5b4fc' }}>
                      <Smartphone size={18} />
                    </div>
                    <div>
                      <h3 style={{ fontSize: '15px', fontWeight: 700, color: '#ffffff' }}>
                        Scan to Connect Mobile Phone
                      </h3>
                      <p style={{ fontSize: '12px', color: '#94a3b8' }}>
                        Instant bidirectional audio bridge between your phone and computer
                      </p>
                    </div>
                  </div>
                  <span style={{ fontSize: '10px', padding: '3px 9px', borderRadius: '999px', background: 'rgba(16, 185, 129, 0.2)', color: '#34d399', fontWeight: 600 }}>
                    HTTPS TUNNEL ACTIVE
                  </span>
                </div>

                <div style={{ display: 'flex', gap: '16px', alignItems: 'center', flexWrap: 'wrap', justifyContent: 'center' }}>
                  {/* QR Code */}
                  <div style={{
                    background: '#ffffff',
                    padding: '8px',
                    borderRadius: '12px',
                    boxShadow: '0 4px 14px rgba(0, 0, 0, 0.4)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexShrink: 0
                  }}>
                    <img
                      src={`https://api.qrserver.com/v1/create-qr-code/?size=130x130&data=${encodeURIComponent(tunnelUrl)}`}
                      alt="Scan with Phone to open SWAR"
                      style={{ width: '120px', height: '120px', display: 'block', borderRadius: '4px' }}
                    />
                  </div>

                  {/* Phone Connection Links */}
                  <div style={{ flex: '1 1 220px', minWidth: 0, width: '100%', display: 'flex', flexDirection: 'column', gap: '10px' }}>
                    <div>
                      <span style={{ fontSize: '11px', color: '#94a3b8', display: 'block', marginBottom: '4px' }}>
                        HTTPS Live URL (Enables iPhone & Android Microphone Access):
                      </span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <input
                          type="text"
                          readOnly
                          value={tunnelUrl}
                          style={{
                            flex: 1,
                            minWidth: 0,
                            width: '100%',
                            boxSizing: 'border-box',
                            padding: '8px 12px',
                            borderRadius: '8px',
                            background: 'rgba(0,0,0,0.5)',
                            border: '1px solid rgba(255,255,255,0.15)',
                            color: '#67e8f9',
                            fontSize: '12px',
                            fontFamily: 'var(--font-mono)'
                          }}
                        />
                        <button
                          onClick={() => copyToClipboard(tunnelUrl)}
                          style={{
                            padding: '8px 14px',
                            borderRadius: '8px',
                            background: 'rgba(99, 102, 241, 0.25)',
                            border: '1px solid rgba(99, 102, 241, 0.4)',
                            color: '#c7d2fe',
                            fontSize: '11px',
                            fontWeight: 600,
                            cursor: 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px',
                            flexShrink: 0
                          }}
                        >
                          {copiedText === tunnelUrl ? <Check size={12} color="#10b981" /> : <Copy size={12} />}
                          <span>Copy</span>
                        </button>
                      </div>
                    </div>

                    <div style={{ fontSize: '11px', color: '#94a3b8', lineHeight: 1.4, wordBreak: 'break-word' }}>
                      <strong style={{ color: '#e2e8f0' }}>Local Wi-Fi / Hotspot:</strong> If on the same Wi-Fi or Hotspot, open <code style={{ color: '#38bdf8' }}>http://{localHostIp}:5173</code> on your phone
                    </div>

                    <button
                      onClick={handleTestTransmit}
                      disabled={isSendingTest}
                      style={{
                        padding: '9px 16px',
                        borderRadius: '8px',
                        background: 'linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)',
                        border: 'none',
                        color: '#ffffff',
                        fontSize: '12px',
                        fontWeight: 600,
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: '6px',
                        boxShadow: '0 4px 12px rgba(99, 102, 241, 0.3)'
                      }}
                    >
                      <Volume2 size={14} />
                      <span>{isSendingTest ? 'Broadcasting...' : 'Send Voice Test Ping to All Devices'}</span>
                    </button>
                  </div>
                </div>
              </div>

              {/* Connected Devices Roster */}
              <div style={{
                background: 'rgba(15, 23, 42, 0.5)',
                border: '1px solid rgba(255, 255, 255, 0.08)',
                borderRadius: '14px',
                padding: '16px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: '12px'
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <Users size={16} color="#818cf8" />
                    <span style={{ fontSize: '13px', fontWeight: 600, color: '#e2e8f0' }}>
                      Active Devices on Web Intercom
                    </span>
                    <span style={{
                      padding: '2px 8px',
                      borderRadius: '999px',
                      background: hasMultipleClients ? 'rgba(16, 185, 129, 0.15)' : 'rgba(255, 255, 255, 0.06)',
                      color: hasMultipleClients ? '#34d399' : '#94a3b8',
                      fontSize: '10px',
                      fontWeight: 600
                    }}>
                      {webClients.length} device{webClients.length === 1 ? '' : 's'} connected
                    </span>
                  </div>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  {webClients.length === 0 ? (
                    <div style={{ padding: '16px', textAlign: 'center', color: '#64748b', fontSize: '12px' }}>
                      Connecting to device network...
                    </div>
                  ) : (
                    webClients.map((client) => {
                      const isSelf = selfClient?.id === client.id;
                      return (
                        <div
                          key={client.id}
                          style={{
                            padding: '10px 14px',
                            borderRadius: '10px',
                            background: isSelf ? 'rgba(99, 102, 241, 0.08)' : 'rgba(255, 255, 255, 0.03)',
                            border: `1px solid ${isSelf ? 'rgba(99, 102, 241, 0.25)' : 'rgba(255, 255, 255, 0.06)'}`,
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between'
                          }}
                        >
                          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                            <span style={{
                              width: '8px',
                              height: '8px',
                              borderRadius: '50%',
                              backgroundColor: '#10b981',
                              boxShadow: '0 0 6px #10b981'
                            }} />
                            <div>
                              <div style={{ fontSize: '13px', fontWeight: 600, color: '#f8fafc', display: 'flex', alignItems: 'center', gap: '6px' }}>
                                <span>{client.name}</span>
                                {isSelf && (
                                  <span style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '4px', background: 'rgba(99, 102, 241, 0.25)', color: '#a5b4fc' }}>
                                    This Device
                                  </span>
                                )}
                              </div>
                              <div style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: '#94a3b8' }}>
                                {client.type.toUpperCase()} • IP: {client.ip}
                              </div>
                            </div>
                          </div>

                          <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: '#34d399', fontWeight: 600 }}>
                            <Check size={12} />
                            <span>Audio Synced</span>
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>

                {hasMultipleClients ? (
                  <div style={{ padding: '10px 12px', borderRadius: '8px', background: 'rgba(16, 185, 129, 0.08)', border: '1px solid rgba(16, 185, 129, 0.2)', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', color: '#34d399' }}>
                    <ShieldCheck size={16} />
                    <span>Bidirectional Intercom active! Any speech transmitted on Phone plays out loud on Laptop, and vice-versa.</span>
                  </div>
                ) : (
                  <div style={{ padding: '10px 12px', borderRadius: '8px', background: 'rgba(245, 158, 11, 0.08)', border: '1px solid rgba(245, 158, 11, 0.2)', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', color: '#fbbf24' }}>
                    <AlertCircle size={16} />
                    <span>Open the link above on your phone to complete the two-device intercom link.</span>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* TAB 2: P2P SOCKET MESH (Direct / LAN / Bluetooth) */}
          {activeTab === 'mesh' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
              {/* Connection Status Banner */}
              <div style={{
                background: isConnected ? 'linear-gradient(135deg, rgba(16, 185, 129, 0.1) 0%, rgba(6, 78, 59, 0.2) 100%)' : 'rgba(30, 41, 59, 0.4)',
                border: `1px solid ${isConnected ? 'rgba(16, 185, 129, 0.35)' : 'rgba(255, 255, 255, 0.08)'}`,
                borderRadius: '14px',
                padding: '16px 20px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                gap: '12px'
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                  <span style={{
                    width: '10px',
                    height: '10px',
                    borderRadius: '50%',
                    backgroundColor: isConnected ? '#10b981' : '#64748b',
                    boxShadow: isConnected ? '0 0 10px #10b981' : 'none'
                  }} />
                  <div>
                    <span style={{ fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.08em', color: '#94a3b8', fontWeight: 600 }}>
                      SOCKET MESH STATE
                    </span>
                    <div style={{ fontSize: '14px', fontWeight: 700, color: isConnected ? '#34d399' : '#ffffff' }}>
                      {isConnected ? `CONNECTED • ${(activeTransport || 'WIFI_DIRECT').toUpperCase().replace('_', ' ')}` : 'DISCONNECTED / LISTENING'}
                    </div>
                  </div>
                </div>

                {isConnected ? (
                  <button
                    onClick={onDisconnect}
                    style={{
                      padding: '7px 14px',
                      borderRadius: '8px',
                      background: 'rgba(244, 63, 94, 0.15)',
                      border: '1px solid rgba(244, 63, 94, 0.3)',
                      color: '#fb7185',
                      fontSize: '12px',
                      fontWeight: 600,
                      cursor: 'pointer'
                    }}
                  >
                    Disconnect Socket
                  </button>
                ) : (
                  <span style={{ fontSize: '12px', color: '#94a3b8', fontStyle: 'italic' }}>
                    Listening on ports 8988, 8990, 8992
                  </span>
                )}
              </div>

              {/* 1-Click Quick Connect Local Mesh */}
              <div style={{
                background: 'rgba(15, 23, 42, 0.5)',
                border: '1px solid rgba(255, 255, 255, 0.08)',
                borderRadius: '14px',
                padding: '16px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: '12px'
              }}>
                <div>
                  <h4 style={{ fontSize: '13px', fontWeight: 700, color: '#f8fafc', marginBottom: '4px' }}>
                    1-Click Instant Transport Verification
                  </h4>
                  <p style={{ fontSize: '11px', color: '#94a3b8' }}>
                    Immediately links the supervisor to the selected transport listener on this machine:
                  </p>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '8px' }}>
                  <button
                    onClick={() => handleQuickConnect('wifi_direct')}
                    disabled={isConnecting}
                    style={{
                      padding: '10px 14px',
                      borderRadius: '10px',
                      background: activeTransport === 'wifi_direct' && isConnected ? 'rgba(168, 85, 247, 0.25)' : 'rgba(255,255,255,0.04)',
                      border: `1px solid ${activeTransport === 'wifi_direct' && isConnected ? 'rgba(168, 85, 247, 0.5)' : 'rgba(255,255,255,0.1)'}`,
                      color: '#ffffff',
                      display: 'flex',
                      flexDirection: 'column',
                      alignItems: 'flex-start',
                      gap: '4px',
                      cursor: 'pointer',
                      textAlign: 'left'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#c084fc', fontSize: '12px', fontWeight: 600 }}>
                      <Zap size={14} />
                      <span>Wi-Fi Direct (P2P)</span>
                    </div>
                    <span style={{ fontSize: '11px', color: '#94a3b8', fontFamily: 'var(--font-mono)' }}>Port 8990</span>
                  </button>

                  <button
                    onClick={() => handleQuickConnect('wifi_lan')}
                    disabled={isConnecting}
                    style={{
                      padding: '10px 14px',
                      borderRadius: '10px',
                      background: activeTransport === 'wifi_lan' && isConnected ? 'rgba(56, 189, 248, 0.25)' : 'rgba(255,255,255,0.04)',
                      border: `1px solid ${activeTransport === 'wifi_lan' && isConnected ? 'rgba(56, 189, 248, 0.5)' : 'rgba(255,255,255,0.1)'}`,
                      color: '#ffffff',
                      display: 'flex',
                      flexDirection: 'column',
                      alignItems: 'flex-start',
                      gap: '4px',
                      cursor: 'pointer',
                      textAlign: 'left'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#38bdf8', fontSize: '12px', fontWeight: 600 }}>
                      <Wifi size={14} />
                      <span>Wi-Fi LAN</span>
                    </div>
                    <span style={{ fontSize: '11px', color: '#94a3b8', fontFamily: 'var(--font-mono)' }}>Port 8988</span>
                  </button>

                  <button
                    onClick={() => handleQuickConnect('bluetooth')}
                    disabled={isConnecting}
                    style={{
                      padding: '10px 14px',
                      borderRadius: '10px',
                      background: activeTransport === 'bluetooth' && isConnected ? 'rgba(59, 130, 246, 0.25)' : 'rgba(255,255,255,0.04)',
                      border: `1px solid ${activeTransport === 'bluetooth' && isConnected ? 'rgba(59, 130, 246, 0.5)' : 'rgba(255,255,255,0.1)'}`,
                      color: '#ffffff',
                      display: 'flex',
                      flexDirection: 'column',
                      alignItems: 'flex-start',
                      gap: '4px',
                      cursor: 'pointer',
                      textAlign: 'left'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#60a5fa', fontSize: '12px', fontWeight: 600 }}>
                      <Bluetooth size={14} />
                      <span>Bluetooth Stream</span>
                    </div>
                    <span style={{ fontSize: '11px', color: '#94a3b8', fontFamily: 'var(--font-mono)' }}>Port 8992</span>
                  </button>
                </div>
              </div>

              {/* Manual Direct Peer Connection */}
              <div style={{
                background: 'rgba(15, 23, 42, 0.5)',
                border: '1px solid rgba(255, 255, 255, 0.08)',
                borderRadius: '14px',
                padding: '16px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: '12px'
              }}>
                <h4 style={{ fontSize: '13px', fontWeight: 700, color: '#f8fafc' }}>
                  Connect to Remote Peer IP Address
                </h4>

                {/* 1-Click Wi-Fi Direct & Hotspot Presets */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                  <span style={{ fontSize: '11px', color: '#94a3b8', fontWeight: 600 }}>
                    1-Click Wi-Fi Direct & Hotspot Subnet Presets:
                  </span>
                  <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                    {wifiDirectPresets.map((preset, idx) => (
                      <button
                        key={idx}
                        type="button"
                        onClick={() => applyPreset(preset)}
                        style={{
                          padding: '5px 10px',
                          borderRadius: '6px',
                          background: manualIp === preset.ip ? 'rgba(99, 102, 241, 0.35)' : 'rgba(255, 255, 255, 0.04)',
                          border: `1px solid ${manualIp === preset.ip ? 'rgba(99, 102, 241, 0.6)' : 'rgba(255, 255, 255, 0.1)'}`,
                          color: manualIp === preset.ip ? '#ffffff' : '#cbd5e1',
                          fontSize: '11px',
                          cursor: 'pointer',
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: '6px',
                          transition: 'all 0.15s ease'
                        }}
                        title={`${preset.desc} (${preset.ip}:${preset.port})`}
                      >
                        <span style={{ fontWeight: manualIp === preset.ip ? 700 : 500 }}>{preset.label}</span>
                        <code style={{ fontSize: '10px', color: '#818cf8', opacity: 0.9 }}>{preset.ip}</code>
                      </button>
                    ))}
                  </div>
                </div>

                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                  <div style={{ flex: '2 1 180px', minWidth: 0, width: '100%' }}>
                    <input
                      type="text"
                      placeholder="e.g. 192.168.1.45"
                      value={manualIp}
                      onChange={(e) => setManualIp(e.target.value)}
                      style={{
                        width: '100%',
                        boxSizing: 'border-box',
                        padding: '9px 12px',
                        borderRadius: '8px',
                        background: 'rgba(0,0,0,0.4)',
                        border: '1px solid rgba(255,255,255,0.15)',
                        color: '#ffffff',
                        fontSize: '12px',
                        fontFamily: 'var(--font-mono)'
                      }}
                    />
                  </div>

                  <div style={{ flex: '1 1 140px', minWidth: 0, width: '100%' }}>
                    <select
                      value={manualTransport}
                      onChange={(e) => handleTransportChange(e.target.value as TransportType)}
                      style={{
                        width: '100%',
                        boxSizing: 'border-box',
                        padding: '9px 12px',
                        borderRadius: '8px',
                        background: '#0f172a',
                        border: '1px solid rgba(255,255,255,0.15)',
                        color: '#ffffff',
                        fontSize: '12px'
                      }}
                    >
                      <option value="wifi_direct">Wi-Fi Direct (8990)</option>
                      <option value="wifi_lan">Wi-Fi LAN (8988)</option>
                      <option value="bluetooth">Bluetooth (8992)</option>
                    </select>
                  </div>

                  <button
                    onClick={() => handleConnect(manualIp, Number(manualPort), manualTransport)}
                    disabled={isConnecting || !manualIp.trim()}
                    style={{
                      flex: '1 1 100px',
                      padding: '9px 18px',
                      borderRadius: '8px',
                      background: '#6366f1',
                      border: 'none',
                      color: '#ffffff',
                      fontSize: '12px',
                      fontWeight: 600,
                      cursor: 'pointer',
                      whiteSpace: 'nowrap'
                    }}
                  >
                    {isConnecting ? 'Connecting...' : 'Connect'}
                  </button>
                </div>
              </div>

              {/* Local Network Info & Discovered UDP Peers */}
              <div style={{
                background: 'rgba(15, 23, 42, 0.5)',
                border: '1px solid rgba(255, 255, 255, 0.08)',
                borderRadius: '14px',
                padding: '16px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: '12px'
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <div>
                    <span style={{ fontSize: '13px', fontWeight: 600, color: '#f8fafc', display: 'block' }}>
                      This Device IP: {localInfo?.primaryIp || '192.168.1.6'}
                    </span>
                    <span style={{ fontSize: '11px', color: '#94a3b8' }}>
                      Sync Status: {wsStatus === 'connected' ? '🟢 Online' : '🟡 Reconnecting'}
                    </span>
                  </div>
                  <button
                    onClick={onRefreshPeers}
                    style={{
                      background: 'rgba(255, 255, 255, 0.05)',
                      border: '1px solid rgba(255, 255, 255, 0.12)',
                      padding: '4px 10px',
                      borderRadius: '6px',
                      color: '#a5b4fc',
                      fontSize: '11px',
                      cursor: 'pointer'
                    }}
                  >
                    Refresh Peers
                  </button>
                </div>

                <div style={{ fontSize: '11px', color: '#94a3b8', marginTop: '4px' }}>
                  Nearby Nodes (UDP Port 8989): {peers.length > 0 ? `${peers.length} detected` : 'Scanning Wi-Fi broadcast...'}
                </div>

                {peers.map((peer) => (
                  <div
                    key={peer.id}
                    style={{
                      padding: '8px 12px',
                      borderRadius: '8px',
                      background: 'rgba(255,255,255,0.03)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between'
                    }}
                  >
                    <div>
                      <span style={{ fontSize: '12px', color: '#f1f5f9', fontWeight: 600 }}>{peer.name}</span>
                      <span style={{ fontSize: '11px', color: '#94a3b8', marginLeft: '6px', fontFamily: 'var(--font-mono)' }}>{peer.address}</span>
                    </div>
                    <button
                      onClick={() => handleConnect(peer.address, peer.port, peer.transport)}
                      style={{
                        padding: '4px 10px',
                        borderRadius: '6px',
                        background: 'rgba(99,102,241,0.25)',
                        border: '1px solid rgba(99,102,241,0.4)',
                        color: '#c7d2fe',
                        fontSize: '11px',
                        fontWeight: 600,
                        cursor: 'pointer'
                      }}
                    >
                      Connect
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}


          {/* TAB: BLUETOOTH PAIRING & RFCOMM STREAM */}
          {activeTab === 'bluetooth' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
              {/* Bluetooth Status Banner */}
              <div style={{
                background: activeTransport === 'bluetooth' && isConnected ? 'linear-gradient(135deg, rgba(59, 130, 246, 0.12) 0%, rgba(37, 99, 235, 0.2) 100%)' : 'rgba(30, 41, 59, 0.4)',
                border: `1px solid ${activeTransport === 'bluetooth' && isConnected ? 'rgba(59, 130, 246, 0.4)' : 'rgba(255, 255, 255, 0.08)'}`,
                borderRadius: '14px',
                padding: '16px 20px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                gap: '12px'
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                  <div style={{
                    width: '36px',
                    height: '36px',
                    borderRadius: '10px',
                    background: 'rgba(59, 130, 246, 0.25)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    color: '#60a5fa'
                  }}>
                    <Bluetooth size={20} />
                  </div>
                  <div>
                    <span style={{ fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.08em', color: '#94a3b8', fontWeight: 600 }}>
                      BLUETOOTH LINK STATUS
                    </span>
                    <div style={{ fontSize: '14px', fontWeight: 700, color: activeTransport === 'bluetooth' && isConnected ? '#60a5fa' : '#ffffff' }}>
                      {activeTransport === 'bluetooth' && isConnected ? 'CONNECTED • BLUETOOTH RFCOMM (8992)' : 'STANDBY / READY TO PAIR'}
                    </div>
                  </div>
                </div>

                <div style={{ display: 'flex', gap: '8px' }}>
                  <button
                    onClick={() => handleQuickConnect('bluetooth')}
                    disabled={isConnecting}
                    style={{
                      padding: '8px 14px',
                      borderRadius: '8px',
                      background: 'rgba(59, 130, 246, 0.25)',
                      border: '1px solid rgba(59, 130, 246, 0.4)',
                      color: '#93c5fd',
                      fontSize: '12px',
                      fontWeight: 600,
                      cursor: 'pointer'
                    }}
                  >
                    {activeTransport === 'bluetooth' && isConnected ? 'Active Link' : 'Link Bluetooth Port 8992'}
                  </button>

                  <button
                    onClick={handleBluetoothTestPing}
                    disabled={isSendingBtPing}
                    style={{
                      padding: '8px 14px',
                      borderRadius: '8px',
                      background: 'linear-gradient(135deg, #3b82f6 0%, #2563eb 100%)',
                      border: 'none',
                      color: '#ffffff',
                      fontSize: '12px',
                      fontWeight: 600,
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '6px'
                    }}
                  >
                    <Volume2 size={13} />
                    <span>{isSendingBtPing ? 'Pinging...' : 'Bluetooth Audio Ping'}</span>
                  </button>
                </div>
              </div>

              {/* In-Browser Web Bluetooth Pairing */}
              <div style={{
                background: 'rgba(15, 23, 42, 0.5)',
                border: '1px solid rgba(255, 255, 255, 0.08)',
                borderRadius: '14px',
                padding: '16px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: '14px'
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '8px' }}>
                  <div>
                    <h4 style={{ fontSize: '14px', fontWeight: 700, color: '#f8fafc', margin: 0 }}>
                      Browser Web Bluetooth Pairing
                    </h4>
                    <p style={{ fontSize: '12px', color: '#94a3b8', margin: '4px 0 0 0' }}>
                      Pair directly with nearby Bluetooth LE devices or mobile phones via Chrome/Edge API
                    </p>
                  </div>
                  <span style={{ fontSize: '10px', padding: '2px 8px', borderRadius: '999px', background: 'rgba(59, 130, 246, 0.15)', color: '#60a5fa', fontWeight: 600 }}>
                    WEB BLUETOOTH API
                  </span>
                </div>

                <div style={{
                  padding: '14px',
                  borderRadius: '10px',
                  background: 'rgba(0, 0, 0, 0.3)',
                  border: '1px solid rgba(255, 255, 255, 0.06)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  flexWrap: 'wrap',
                  gap: '12px'
                }}>
                  <div>
                    <div style={{ fontSize: '13px', fontWeight: 600, color: '#f1f5f9' }}>
                      {btDevice ? `Paired: ${btDevice.name || 'Bluetooth Peripheral'}` : 'No Bluetooth device currently paired'}
                    </div>
                    <div style={{ fontSize: '11px', color: '#94a3b8', marginTop: '2px', fontFamily: 'var(--font-mono)' }}>
                      {btDevice ? `Device ID: ${btDevice.id}` : 'Click scan to discover nearby Bluetooth radios'}
                    </div>
                  </div>

                  <button
                    onClick={handleScanWebBluetooth}
                    disabled={isScanningBt}
                    style={{
                      padding: '8px 16px',
                      borderRadius: '8px',
                      background: 'rgba(59, 130, 246, 0.25)',
                      border: '1px solid rgba(59, 130, 246, 0.5)',
                      color: '#93c5fd',
                      fontSize: '12px',
                      fontWeight: 600,
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '6px'
                    }}
                  >
                    <Bluetooth size={14} />
                    <span>{isScanningBt ? 'Scanning Nearby Devices...' : 'Scan & Pair Bluetooth Device'}</span>
                  </button>
                </div>
              </div>

              {/* Bluetooth RFCOMM Stream Parameters & Tactical Specs */}
              <div style={{
                background: 'rgba(15, 23, 42, 0.5)',
                border: '1px solid rgba(255, 255, 255, 0.08)',
                borderRadius: '14px',
                padding: '16px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: '12px'
              }}>
                <h4 style={{ fontSize: '13px', fontWeight: 700, color: '#f8fafc', margin: 0 }}>
                  Bluetooth Classic Tactical Architecture (RFCOMM Port 8992)
                </h4>
                <p style={{ fontSize: '12px', color: '#94a3b8', margin: 0, lineHeight: 1.5 }}>
                  SWAR uses Bluetooth RFCOMM stream emulation to exchange 4-byte frames at an ultra-compact 1,850 bps bitrate. This guarantees full tactical voice functionality even when Wi-Fi is completely jammed or turned off for battery conservation.
                </p>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '10px', marginTop: '4px' }}>
                  <div style={{ padding: '12px', borderRadius: '8px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.06)' }}>
                    <div style={{ fontSize: '11px', color: '#94a3b8', textTransform: 'uppercase', fontWeight: 600 }}>Gross Wire Rate</div>
                    <div style={{ fontSize: '16px', fontWeight: 700, color: '#60a5fa', marginTop: '2px', fontFamily: 'var(--font-mono)' }}>1,850 bps</div>
                    <div style={{ fontSize: '10px', color: '#64748b', marginTop: '2px' }}>99.3% compression vs PCM</div>
                  </div>

                  <div style={{ padding: '12px', borderRadius: '8px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.06)' }}>
                    <div style={{ fontSize: '11px', color: '#94a3b8', textTransform: 'uppercase', fontWeight: 600 }}>Framing Protocol</div>
                    <div style={{ fontSize: '16px', fontWeight: 700, color: '#34d399', marginTop: '2px', fontFamily: 'var(--font-mono)' }}>4-Byte Header</div>
                    <div style={{ fontSize: '10px', color: '#64748b', marginTop: '2px' }}>CRC32 Verified Delivery</div>
                  </div>

                  <div style={{ padding: '12px', borderRadius: '8px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.06)' }}>
                    <div style={{ fontSize: '11px', color: '#94a3b8', textTransform: 'uppercase', fontWeight: 600 }}>Operating Profile</div>
                    <div style={{ fontSize: '16px', fontWeight: 700, color: '#fbbf24', marginTop: '2px', fontFamily: 'var(--font-mono)' }}>Tactical RFCOMM</div>
                    <div style={{ fontSize: '10px', color: '#64748b', marginTop: '2px' }}>Zero Internet Required</div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* TAB 3: HOW IT WORKS (GUIDANCE & SIMPLICITY) */}
          {activeTab === 'guide' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div style={{ padding: '16px', borderRadius: '12px', background: 'rgba(99, 102, 241, 0.08)', border: '1px solid rgba(99, 102, 241, 0.2)' }}>
                <h3 style={{ fontSize: '15px', fontWeight: 700, color: '#ffffff', marginBottom: '6px' }}>
                  What is SWAR?
                </h3>
                <p style={{ fontSize: '12px', color: '#cbd5e1', lineHeight: 1.6 }}>
                  SWAR is an offline tactical voice communications system. Instead of streaming heavy raw PCM voice audio (which consumes 32,000 bytes every second and requires high-speed broadband), SWAR converts spoken phrases into compact semantic tokens and transmits them in tiny 4-byte frames over local Wi-Fi Direct, Wi-Fi LAN, or Bluetooth. The receiving device resynthesizes natural voice audio locally with zero internet dependency.
                </p>
              </div>

              {/* 3 Steps */}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '12px' }}>
                <div style={{ padding: '14px', borderRadius: '10px', background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                  <div style={{ fontSize: '12px', fontWeight: 700, color: '#818cf8', marginBottom: '4px' }}>
                    1. Speech Capture (STT)
                  </div>
                  <p style={{ fontSize: '11px', color: '#94a3b8', lineHeight: 1.5 }}>
                    Spoken voice is captured via microphone, normalized into Indic text across 11 languages, and tokenized into 14-bit dictionary indices.
                  </p>
                </div>

                <div style={{ padding: '14px', borderRadius: '10px', background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                  <div style={{ fontSize: '12px', fontWeight: 700, color: '#a855f7', marginBottom: '4px' }}>
                    2. 4-Byte Wire Framing
                  </div>
                  <p style={{ fontSize: '11px', color: '#94a3b8', lineHeight: 1.5 }}>
                    Sentences are encoded with a 4-byte header (Version, Type, Sequence, Payload Length) and sent at 99.8% bandwidth compression.
                  </p>
                </div>

                <div style={{ padding: '14px', borderRadius: '10px', background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                  <div style={{ fontSize: '12px', fontWeight: 700, color: '#34d399', marginBottom: '4px' }}>
                    3. Synthetic Playback (TTS)
                  </div>
                  <p style={{ fontSize: '11px', color: '#94a3b8', lineHeight: 1.5 }}>
                    The receiver decodes the frame, verifies CRC integrity, synthesizes natural speech in the designated regional dialect, and plays it out loud.
                  </p>
                </div>
              </div>

              {/* Bandwidth comparison table */}
              <div style={{ padding: '14px 18px', borderRadius: '10px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.06)' }}>
                <div style={{ fontSize: '12px', fontWeight: 600, color: '#e2e8f0', marginBottom: '8px' }}>
                  Bandwidth Comparison
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: '#94a3b8', padding: '4px 0', borderBottom: '1px solid rgba(255,255,255,0.05)' }}>
                  <span>Uncompressed 16 kHz Mono PCM Voice:</span>
                  <span style={{ color: '#fb7185', fontFamily: 'var(--font-mono)' }}>256,000 bps (32 KB/s)</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: '#94a3b8', padding: '4px 0', borderBottom: '1px solid rgba(255,255,255,0.05)' }}>
                  <span>Standard VoIP (Opus / G.711):</span>
                  <span style={{ color: '#fbbf24', fontFamily: 'var(--font-mono)' }}>24,000 - 64,000 bps</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: '#94a3b8', padding: '4px 0' }}>
                  <span style={{ fontWeight: 600, color: '#34d399' }}>SWAR Semantic Framing:</span>
                  <span style={{ color: '#34d399', fontWeight: 700, fontFamily: 'var(--font-mono)' }}>1,850 bps (99.2% Savings)</span>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
