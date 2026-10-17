import React, { useState } from 'react';
import { Download, Zap, Gauge, Check } from 'lucide-react';
import { RunReport } from '../types';

interface ComparisonGaugeProps {
  actualWireBps: number;
  compressionRatio: number;
  lastReport: RunReport | null;
}

export const ComparisonGauge: React.FC<ComparisonGaugeProps> = ({
  actualWireBps,
  compressionRatio,
  lastReport,
}) => {
  const [downloaded, setDownloaded] = useState(false);
  const pcmBps = 256000;
  // Percentage of 256 kbps consumed by SWAR (e.g. 1000 / 256000 = ~0.39%)
  const percentageOfPcm = actualWireBps > 0 ? (actualWireBps / pcmBps) * 100 : 0;

  const handleExportJson = () => {
    if (!lastReport) return;
    const blob = new Blob([JSON.stringify(lastReport, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `voicebridge_run_${lastReport.run_id.slice(0, 8)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    setDownloaded(true);
    setTimeout(() => setDownloaded(false), 2000);
  };

  return (
    <div className="comparison-footer-bar">
      <div style={{ display: 'flex', alignItems: 'center', gap: '16px', flex: 1, minWidth: '280px' }}>
        <div style={{ padding: '10px', borderRadius: '16px', background: 'rgba(16,185,129,0.15)', color: '#34d399', border: '1px solid rgba(16,185,129,0.3)' }}>
          <Zap size={22} />
        </div>

        <div style={{ flex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px', flexWrap: 'wrap', gap: '6px' }}>
            <span style={{ fontSize: '12px', fontWeight: 700, color: '#f8fafc', letterSpacing: '0.04em', textTransform: 'uppercase', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Gauge size={14} color="#34d399" /> Bandwidth Efficiency vs Raw PCM
            </span>
            <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: '#94a3b8' }}>
              On-Air Wire: <strong style={{ color: '#ffffff' }}>{actualWireBps > 0 ? `${actualWireBps} bps` : '--'}</strong> / PCM: <span style={{ color: '#64748b' }}>256k bps</span>
            </span>
          </div>

          <div className="gauge-bar-wrapper">
            <div
              className="gauge-fill"
              style={{ width: `${Math.max(1.5, Math.min(100, percentageOfPcm * 10))}%` }}
              title={`Consumes only ${percentageOfPcm.toFixed(2)}% of raw speech bandwidth`}
            />
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: '#64748b' }}>
            <span>
              {actualWireBps > 0
                ? `Consuming only ${percentageOfPcm.toFixed(2)}% of uncompressed speech channel capacity`
                : 'Transmit audio to measure real-time spectral efficiency'}
            </span>
            <span style={{ fontFamily: 'var(--font-mono)', color: '#818cf8' }}>Target: 0.5 – 2.0 kbps</span>
          </div>
        </div>
      </div>

      {/* Compression Multiplier Card */}
      <div className="compression-multiplier-pill">
        <div className="mult-num">
          {compressionRatio > 0 ? `${compressionRatio.toFixed(0)}×` : '256×'}
        </div>
        <div className="mult-label">Compression</div>
      </div>

      {/* Export Action Button */}
      <div>
        <button
          onClick={handleExportJson}
          disabled={!lastReport}
          className="secondary-pill-btn"
          style={{
            padding: '10px 20px',
            background: downloaded ? 'rgba(16,185,129,0.2)' : 'rgba(255,255,255,0.08)',
            borderColor: downloaded ? '#10b981' : 'rgba(255,255,255,0.15)',
            color: downloaded ? '#6ee7b7' : '#ffffff'
          }}
          title="Download reproducible run telemetry report JSON"
        >
          {downloaded ? (
            <>
              <Check size={14} color="#10b981" />
              <span>Report Exported!</span>
            </>
          ) : (
            <>
              <Download size={14} />
              <span>Export Report JSON</span>
            </>
          )}
        </button>
      </div>
    </div>
  );
};
