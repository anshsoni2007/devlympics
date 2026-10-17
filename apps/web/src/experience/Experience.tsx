import { useContext, useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import {
  ArrowDown,
  ArrowRight,
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
  Menu,
  Pause,
  Play,
  X,
  ZoomIn
} from 'lucide-react';
import { DEFAULT_APK_DOWNLOAD_URL } from '../config';
import { BrandMark, MotionContext, useInView, WaveBars } from './primitives';
import { Hero } from './Hero';
import { PushToTalk } from './PushToTalk';

gsap.registerPlugin(ScrollTrigger);

function Navigation({ onSwitchView }: { onSwitchView?: (view: 'transceiver' | 'experience' | 'studio') => void }) {
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        menuRef.current?.focus();
      }
    };
    window.addEventListener('keydown', close);
    return () => window.removeEventListener('keydown', close);
  }, [open]);

  return (
    <div className="navigation-anchor">
      <header className="site-header">
        <a href="#home" className="brand" aria-label="SWAR home">
          <BrandMark />
          <span>SWAR</span>
        </a>

        <nav className="desktop-nav" aria-label="Main navigation">
          <a href="#technology">Technology</a>
          <a href="#demo">Demo</a>
          <a href="#mobile-app">Mobile App</a>
          <a href="#languages">Languages</a>
          <a href="#faq">FAQ</a>
        </nav>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          {onSwitchView && (
            <button
              onClick={() => onSwitchView('transceiver')}
              className="nav-apk-btn"
              style={{
                backgroundColor: '#c3f53b',
                color: '#0c140a',
                borderColor: '#c3f53b',
                cursor: 'pointer',
                fontWeight: 700
              }}
              title="Open Retro Pixel Handheld Communicator"
            >
              <span>📟 Communicator</span>
            </button>
          )}

          <a
            className="nav-apk-btn"
            href={DEFAULT_APK_DOWNLOAD_URL}
            download={DEFAULT_APK_DOWNLOAD_URL.startsWith('/') ? 'swar-debug.apk' : undefined}
            title="Download SWAR Android APK"
          >
            <ArrowDown size={15} strokeWidth={2.5} />
            <span>Download APK</span>
          </a>
        </div>

        <button
          ref={menuRef}
          className="menu-toggle"
          aria-label={open ? 'Close menu' : 'Open menu'}
          aria-expanded={open}
          aria-controls="mobile-navigation"
          onClick={() => setOpen(!open)}
        >
          {open ? <X size={20} /> : <Menu size={20} />}
        </button>

        {open && (
          <nav
            id="mobile-navigation"
            className="mobile-navigation"
            aria-label="Mobile navigation"
            onClick={() => setOpen(false)}
          >
            <a href="#technology">
              Technology <ArrowRight size={16} />
            </a>
            <a href="#demo">
              Walkie-Talkie Demo <ArrowRight size={16} />
            </a>
            <a href="#mobile-app">
              Mobile App <ArrowRight size={16} />
            </a>
            <a href="#languages">
              Languages <ArrowRight size={16} />
            </a>
            <a href="#faq">
              FAQ <ArrowRight size={16} />
            </a>
            <a
              href={DEFAULT_APK_DOWNLOAD_URL}
              className="mobile-apk-download-btn"
              download={DEFAULT_APK_DOWNLOAD_URL.startsWith('/') ? 'swar-debug.apk' : undefined}
            >
              <ArrowDown size={16} strokeWidth={2.5} /> Download APK (v1.0)
            </a>
          </nav>
        )}
      </header>
    </div>
  );
}

const stages = [
  {
    step: 'STAGE 01 / VAD',
    title: 'Speak naturally.',
    stageTag: 'ON-DEVICE CAPTURE',
    sampleClass: 'voice',
    sample: (
      <>
        <span className="eyebrow">16 kHz PCM AUDIO INPUT</span>
        <span>“हमें तुरंत सहायता चाहिए, मुख्य द्वार पर टीम भेजें।”</span>
      </>
    ),
    description:
      'Voice activity detection analyzes microphone audio in real time on the device, isolating spoken disaster phrases and discarding ambient acoustic noise.'
  },
  {
    step: 'STAGE 02 / ASR',
    title: 'Find the words.',
    stageTag: 'SEMANTIC EXTRACTION',
    sampleClass: 'tokens',
    sample: (
      <span className="tokens">
        [VAD_START] URGENT_HELP [SEP] MAIN_GATE [LANG:HI] [CRC:0x4E2A] [VAD_END]
      </span>
    ),
    description:
      'Offline neural speech recognition (AI4Bharat IndicConformer) transcribes speech into compact semantic text tokens, shrinking data by 98% compared to raw audio.'
  },
  {
    step: 'STAGE 03 / PROTOCOL',
    title: 'Pack the meaning.',
    stageTag: 'ITP/1 RADIO FRAMING',
    sampleClass: 'packet',
    sample: (
      <>
        <div className="packet-cell">ITP/1 HEADER</div>
        <div className="packet-cell">TEXT FRAME (50B)</div>
        <div className="packet-cell">CRC-16 INTEGRITY</div>
      </>
    ),
    description:
      'Lightweight binary framing adds sequence IDs, language tags, sender hop counters, and CRC-16 checksums so packets survive harsh, lossy radio channels.'
  },
  {
    step: 'STAGE 04 / TTS',
    title: 'Hear it again.',
    stageTag: 'VOICE RECONSTRUCTION',
    sampleClass: 'received',
    sample: (
      <>
        <WaveBars active count={21} />
        <span>Resynthesized Indic Speech</span>
      </>
    ),
    description:
      'The receiver verifies packet integrity and instantly synthesizes natural speech in the designated language using on-device neural Indic-TTS.'
  }
];

function TechnologySection() {
  const sectionRef = useRef<HTMLElement>(null);
  const stripRef = useRef<HTMLDivElement>(null);
  const [activeStage, setActiveStage] = useState(0);

  useEffect(() => {
    const sec = sectionRef.current;
    const strip = stripRef.current;
    if (!sec || !strip) return;

    const ctx = gsap.context(() => {
      const cards = strip.querySelectorAll('.gallery-project-wrap');
      const count = cards.length;

      const getStep = () => {
        if (cards.length < 2) return 0;
        return (cards[1] as HTMLElement).offsetLeft - (cards[0] as HTMLElement).offsetLeft;
      };

      const getTotalScroll = () => {
        return getStep() * (count - 1);
      };

      gsap.to(strip, {
        x: () => -getTotalScroll(),
        ease: 'none',
        scrollTrigger: {
          id: 'techGalleryScroll',
          trigger: sec,
          pin: true,
          scrub: 0.8,
          snap: {
            snapTo: 1 / (count - 1),
            duration: { min: 0.25, max: 0.5 },
            delay: 0.05,
            ease: 'power1.inOut'
          },
          start: 'center center',
          end: () => `+=${window.innerHeight * 2.8}`,
          invalidateOnRefresh: true,
          onUpdate: (self) => {
            const activeIdx = Math.min(
              Math.max(Math.round(self.progress * (count - 1)), 0),
              count - 1
            );
            setActiveStage(activeIdx);
          }
        }
      });
    }, sec);

    return () => ctx.revert();
  }, []);

  const scrollToStage = (targetIdx: number) => {
    const st = ScrollTrigger.getById('techGalleryScroll');
    if (!st) return;
    const count = stages.length;
    const progress = targetIdx / (count - 1);
    const targetY = st.start + progress * (st.end - st.start);
    window.scrollTo({ top: targetY, behavior: 'smooth' });
  };

  return (
    <section ref={sectionRef} className="technology-gallery-section" id="technology">
      <div className="technology-gallery-pin">
        <div className="technology-gallery-header">
          <div className="section-topline">
            <span className="eyebrow">01 / HOW SWAR WORKS</span>
            <div className="gallery-step-indicator">
              <span className="step-count">STAGE 0{activeStage + 1} / 04</span>
              <div className="step-bars" aria-label={`Stage ${activeStage + 1} of 4`}>
                {stages.map((_, i) => (
                  <button
                    key={i}
                    type="button"
                    className={`step-bar ${i === activeStage ? 'active' : i < activeStage ? 'passed' : ''}`}
                    onClick={() => scrollToStage(i)}
                    aria-label={`Jump to stage ${i + 1}`}
                  />
                ))}
              </div>
            </div>
          </div>

          <div className="technology-gallery-heading">
            <h2>
              Carry the words.
              <br />
              <em>Leave the rest.</em>
            </h2>
          </div>
        </div>

        <div className="horiz-gallery-wrapper">
          <div ref={stripRef} className="horiz-gallery-strip">
            {stages.map((stage, idx) => (
              <div
                key={idx}
                className={`gallery-project-wrap ${idx === activeStage ? 'is-active' : ''}`}
                onClick={() => {
                  if (idx !== activeStage) scrollToStage(idx);
                }}
              >
                <article className="gallery-stage-card">
                  <div className="gallery-card-topline">
                    <span className="eyebrow">{stage.step}</span>
                    <span className="stage-badge">{stage.stageTag}</span>
                  </div>
                  <h3>{stage.title}</h3>
                  <div className={`stage-sample ${stage.sampleClass}`}>
                    {stage.sample}
                  </div>
                  <p className="stage-desc">{stage.description}</p>
                  <div className="gallery-card-footer">
                    <span className="stage-num">0{idx + 1} / 04</span>
                    <span className="stage-tech-tag">ITP/1 · 1.8 KBPS MESH</span>
                  </div>
                </article>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}

const appScreenshots = [
  {
    title: 'Offline Radar Mesh',
    subtitle: 'Zero-hop autonomous radar discovery of peer nodes within 100m',
    tag: 'RADAR DISCOVERY',
    src: '/screenshots/pixel9a_radar_discovery_540w.png',
    fullSrc: '/screenshots/pixel9a_radar_discovery.png',
    device: 'Pixel 9a · Wi-Fi P2P'
  },
  {
    title: 'Voice Transceiver & PTT',
    subtitle: 'Half-duplex speech capture with swipe-lock & Indic neural pipeline',
    tag: 'TRANSCEIVER',
    src: '/screenshots/pixel9a_transceiver_540w.png',
    fullSrc: '/screenshots/pixel9a_transceiver.png',
    device: 'Pixel 9a · Half-Duplex'
  },
  {
    title: 'Peer Authorization Handshake',
    subtitle: 'Mutual challenge modal with sonar beacon & 30s security countdown',
    tag: 'MUTUAL AUTH',
    src: '/screenshots/pixel9a_connection_auth_540w.png',
    fullSrc: '/screenshots/pixel9a_connection_auth.png',
    device: 'Pixel 9a · Handshake'
  },
  {
    title: 'Direct P2P Link Channel',
    subtitle: 'Peer link request stream initiated to target node over Wi-Fi LAN',
    tag: 'P2P SOCKET',
    src: '/screenshots/pixel9a_outgoing_link_540w.png',
    fullSrc: '/screenshots/pixel9a_outgoing_link.png',
    device: 'Pixel 9a · Direct Link'
  },
  {
    title: 'On-Device Neural Engines',
    subtitle: 'IndicConformer STT & IndicTTS voice models cached offline',
    tag: 'NEURAL SPEECH',
    src: '/screenshots/pixel9a_neural_models_540w.png',
    fullSrc: '/screenshots/pixel9a_neural_models.png',
    device: 'Pixel 9a · Offline AI'
  },
  {
    title: 'Tactical Mesh Dispatch',
    subtitle: 'Zero-internet transliterated text & audio dispatch across nodes',
    tag: 'TACTICAL DISPATCH',
    src: '/screenshots/pixel9a_tactical_dispatch_540w.png',
    fullSrc: '/screenshots/pixel9a_tactical_dispatch.png',
    device: 'Pixel 9a · Mesh Dispatch'
  },
  {
    title: 'System Specs & Telemetry',
    subtitle: 'Real-time ITP frame counters, RAM usage & radio protocol stats',
    tag: 'TELEMETRY',
    src: '/screenshots/pixel9a_specs_telemetry_540w.png',
    fullSrc: '/screenshots/pixel9a_specs_telemetry.png',
    device: 'Pixel 9a · Telemetry'
  },
  {
    title: 'Hardware Node Identity',
    subtitle: 'Callsign configuration, socket bindings & TCP port 8785 listeners',
    tag: 'NODE IDENTITY',
    src: '/screenshots/pixel9a_node_identity_540w.png',
    fullSrc: '/screenshots/pixel9a_node_identity.png',
    device: 'Pixel 9a · Node Profile'
  }
];

function MobileAppSection() {
  const { ref, visible } = useInView<HTMLElement>();
  const [isPaused, setIsPaused] = useState(false);
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);
  const motionEnabled = useContext(MotionContext);

  // Duplicate for seamless infinite side-to-side marquee
  const marqueeItems = [...appScreenshots, ...appScreenshots];

  const activeModalItem = selectedIdx !== null ? appScreenshots[selectedIdx] : null;

  const handleNext = () => {
    if (selectedIdx === null) return;
    setSelectedIdx((selectedIdx + 1) % appScreenshots.length);
  };

  const handlePrev = () => {
    if (selectedIdx === null) return;
    setSelectedIdx((selectedIdx - 1 + appScreenshots.length) % appScreenshots.length);
  };

  useEffect(() => {
    if (selectedIdx === null) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setSelectedIdx(null);
      if (e.key === 'ArrowRight') handleNext();
      if (e.key === 'ArrowLeft') handlePrev();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [selectedIdx]);

  return (
    <section
      ref={ref}
      className="chapter mobile-app-section"
      id="mobile-app"
      data-visible={visible}
    >
      <div className="section-topline">
        <span className="eyebrow">03 / THE ANDROID FIELD APP</span>
        <span className="eyebrow">OFFLINE RADAR & AUDIO TRANSCEIVER</span>
      </div>
      <div className="section-heading">
        <h2>
          Built for the field.
          <br />
          <em>Zero towers needed.</em>
        </h2>
        <p>
          Live captures of the SWAR Android app operating completely offline.
          From radar peer discovery to instant push-to-talk, devices form an encrypted peer-to-peer
          mesh over Wi-Fi Direct and Bluetooth Classic without internet or cellular connectivity.
        </p>
      </div>

      <div
        className={`infinite-marquee-container ${!motionEnabled || isPaused || selectedIdx !== null ? 'is-paused' : ''}`}
        onMouseEnter={() => setIsPaused(true)}
        onMouseLeave={() => setIsPaused(false)}
        onTouchStart={() => setIsPaused(true)}
        onTouchEnd={() => setIsPaused(false)}
      >
        <div className="marquee-track">
          {marqueeItems.map((item, index) => (
            <div
              key={`${item.title}-${index}`}
              className="mobile-mockup-card"
              onClick={() => setSelectedIdx(index % appScreenshots.length)}
              role="button"
              tabIndex={0}
              aria-label={`Inspect ${item.title} snapshot`}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  setSelectedIdx(index % appScreenshots.length);
                }
              }}
            >
              <div className="phone-bezel">
                <div className="phone-screen">
                  <div className="phone-notch">
                    <span className="notch-camera" />
                  </div>
                  <img
                    src={item.src}
                    srcSet={`${item.src} 1x, ${item.fullSrc} 2x`}
                    alt={item.title}
                    loading="lazy"
                    className="phone-img"
                    width={540}
                    height={1212}
                  />
                  <div className="phone-hover-overlay">
                    <span className="expand-pill">
                      <ZoomIn size={13} /> Click to Inspect High-Res
                    </span>
                  </div>
                </div>
              </div>
              <div className="mockup-meta">
                <div className="mockup-topline">
                  <span className="mockup-tag">{item.tag}</span>
                  <span className="mockup-device">{item.device}</span>
                </div>
                <h4>{item.title}</h4>
                <p>{item.subtitle}</p>
              </div>
            </div>
          ))}
        </div>
      </div>

      {activeModalItem && (
        <div
          className="screenshot-lightbox-backdrop"
          onClick={() => setSelectedIdx(null)}
        >
          <div
            className="screenshot-lightbox-dialog"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              className="lightbox-close-btn"
              onClick={() => setSelectedIdx(null)}
              aria-label="Close high-res preview"
            >
              <X size={20} />
            </button>

            <button
              className="lightbox-nav-btn lightbox-prev-btn"
              onClick={handlePrev}
              aria-label="Previous snapshot"
            >
              <ChevronLeft size={24} />
            </button>

            <div className="lightbox-phone-wrapper">
              <div className="phone-bezel lightbox-phone-bezel">
                <div className="phone-screen">
                  <div className="phone-notch">
                    <span className="notch-camera" />
                  </div>
                  <img
                    src={activeModalItem.fullSrc}
                    alt={activeModalItem.title}
                    className="lightbox-phone-img"
                  />
                </div>
              </div>
            </div>

            <button
              className="lightbox-nav-btn lightbox-next-btn"
              onClick={handleNext}
              aria-label="Next snapshot"
            >
              <ChevronRight size={24} />
            </button>

            <div className="lightbox-meta">
              <div className="mockup-topline">
                <span className="mockup-tag">{activeModalItem.tag}</span>
                <span className="mockup-device">{activeModalItem.device}</span>
              </div>
              <h3>{activeModalItem.title}</h3>
              <p>{activeModalItem.subtitle}</p>
              <span className="lightbox-counter">
                {((selectedIdx ?? 0) + 1)} / {appScreenshots.length} · Live Pixel 9a Native 1080p
              </span>
            </div>
          </div>
        </div>
      )}

      <div className="marquee-footer">
        <div className="metadata-tags">
          <span>WI-FI DIRECT P2P</span>
          <span>BLUETOOTH RFCOMM</span>
          <span>WI-FI LAN TCP (8988)</span>
          <span>ITP/1 RADIO FRAMES</span>
          <span>AI4BHARAT INDICCONFORMER</span>
        </div>
        <div className="marquee-cta-row">
          <div className="marquee-controls-group">
            <button
              type="button"
              className="marquee-pause-toggle"
              onClick={() => setIsPaused(!isPaused)}
              aria-label={isPaused ? 'Resume screenshot carousel' : 'Pause screenshot carousel'}
              aria-pressed={isPaused}
            >
              {isPaused ? <Play size={12} /> : <Pause size={12} />}
              <span>{isPaused ? 'RESUME CAROUSEL' : 'PAUSE CAROUSEL'}</span>
            </button>
            <p>
              Hover, touch or toggle to pause · Click any card to inspect native 1080p high-res. All screens captured live on Google Pixel 9a test device.
            </p>
          </div>
          <a
            href={DEFAULT_APK_DOWNLOAD_URL}
            className="button button-primary download-pill-btn"
            download={DEFAULT_APK_DOWNLOAD_URL.startsWith('/') ? 'swar-debug.apk' : undefined}
          >
            <ArrowDown size={15} /> Download Android APK
          </a>
        </div>
      </div>
    </section>
  );
}

const languages = [
  { name: 'Hindi', code: 'hi', text: 'हमें तुरंत सहायता चाहिए, मुख्य द्वार पर मेडिकल टीम भेजें।', translation: 'URGENT MEDICAL AID AT MAIN GATE' },
  { name: 'English', code: 'en', text: 'Signal acquired. Route is clear, advance team carefully.', translation: 'OPERATIONAL STATUS & ROUTE CLEAR' },
  { name: 'Tamil', code: 'ta', text: 'உடனடி உதவி தேவை, மீட்புக் குழுவை விரைவாக அனுப்பவும்.', translation: 'RESCUE DISPATCH REQUEST' },
  { name: 'Bengali', code: 'bn', text: 'আমাদের অবিলম্বে সাহায্য দরকার, জল ও ওষুধ পাঠান।', translation: 'URGENT RELIEF SUPPLIES NEEDED' },
  { name: 'Telugu', code: 'te', text: 'మాకు వెంటనే సహాయక బృందం అవసరం, రక్షించండి.', translation: 'EMERGENCY SEARCH AND RESCUE' },
];

function LanguageSection() {
  const { ref, visible } = useInView<HTMLElement>();
  const [selectedLang, setSelectedLang] = useState(0);

  return (
    <section
      ref={ref}
      className="chapter language-section"
      id="languages"
      data-visible={visible}
    >
      <p className="eyebrow">04 / MULTILINGUAL INDIA</p>
      <h2>
        Many mother tongues.
        <br />
        <em>One shared meaning.</em>
      </h2>
      <div className="language-display">
        <p lang={languages[selectedLang].code} key={selectedLang}>
          “{languages[selectedLang].text}”
        </p>
        <span className="eyebrow">
          {languages[selectedLang].name.toUpperCase()} ·{' '}
          {languages[selectedLang].translation}
        </span>
      </div>
      <div
        className="language-options"
        role="group"
        aria-label="Choose a language"
      >
        {languages.map((item, idx) => (
          <button
            key={item.code}
            aria-pressed={idx === selectedLang}
            onClick={() => setSelectedLang(idx)}
          >
            {item.name}
            <span />
          </button>
        ))}
      </div>
      <p className="language-note">
        On-device AI4Bharat IndicConformer speech recognition and synthetic voice
        playback tailored for Indian languages, accents, and Hinglish code-switching.
      </p>
    </section>
  );
}

function DemoSection() {
  const { ref, visible } = useInView<HTMLElement>();

  return (
    <section
      ref={ref}
      className="chapter demo-section"
      id="demo"
      data-visible={visible}
    >
      <div className="section-topline">
        <span className="eyebrow">02 / INTERACTIVE DEMO</span>
        <span className="eyebrow">WALKIE-TALKIE SIMULATION</span>
      </div>
      <div className="demo-layout">
        <div className="demo-intro">
          <h2>
            Push.
            <br />
            Speak.
            <br />
            <em>Connect.</em>
          </h2>
          <p>
            Hold down the push-to-talk button to simulate voice input.
            Release to observe speech tokenize into an ITP/1 frame,
            and resynthesize into audible speech offline.
          </p>
        </div>
        <PushToTalk />
      </div>
    </section>
  );
}

const faqs = [
  {
    q: 'How does SWAR transmit voice without internet or cellular network?',
    tag: 'OFFLINE MESH',
    a: "SWAR bypasses cellular towers and internet service providers entirely. It establishes direct device-to-device radio links using Android's native Wi-Fi Direct (P2P), local Wi-Fi LAN sockets (TCP port 8988 / UDP 8989), and Bluetooth Classic RFCOMM (port 8992). Nearby smartphones discover each other automatically and communicate in an ad-hoc local mesh."
  },
  {
    q: 'How does SWAR achieve 98% bandwidth reduction?',
    tag: 'AI COMPRESSION',
    a: 'Traditional voice applications stream continuous acoustic waveforms (such as Opus or PCM) requiring 64,000 to 256,000 bits/sec, which drops when signal strength is low. SWAR performs on-device speech-to-text recognition via AI4Bharat IndicConformer, compressing sentences into ultra-compact semantic tokens (~1.8 kbps). A 50KB voice recording shrinks into a ~50-byte ITP/1 packet that transmits reliably even under extreme packet loss.'
  },
  {
    q: 'Which Indian languages and scripts are supported?',
    tag: 'INDIC AI',
    a: 'SWAR supports 22 Indian languages including Hindi, Tamil, Telugu, Bengali, Marathi, Gujarati, Kannada, and Indian English. It natively processes regional accents and mixed-language speech (such as Hinglish code-switching), ensuring seamless communication for multi-state disaster rescue teams.'
  },
  {
    q: 'What is the physical range between phones offline?',
    tag: 'RANGE & RELAY',
    a: 'Direct peer-to-peer Wi-Fi Direct links achieve 80 to 120 meters line-of-sight outdoors, while Bluetooth Classic spans 20 to 30 meters through light obstacles. In an emergency mesh deployment, SWAR packets hop across intermediate devices (multi-hop store-and-forward relay), extending voice coverage across entire disaster response sectors.'
  },
  {
    q: 'Does SWAR require special hardware or root access?',
    tag: 'ACCESSIBILITY',
    a: 'No special hardware or root access is needed. SWAR runs on standard, off-the-shelf Android smartphones (Android 9.0 Pie or higher). No external antennas or proprietary radios are required; it leverages the built-in Wi-Fi and Bluetooth chipsets already in every responder’s pocket.'
  },
  {
    q: 'How do I download and install the app on my Android device?',
    tag: 'INSTALLATION',
    a: 'Click the "Download APK" button in the navigation bar to download swar-debug.apk directly to your phone. Open the APK, allow installation from your browser or file manager if prompted, grant microphone and location/nearby permissions, and your device is ready to connect offline.'
  }
];

function FaqSection() {
  const { ref, visible } = useInView<HTMLElement>();
  const [openIndex, setOpenIndex] = useState<number | null>(0);

  const toggleFaq = (idx: number) => {
    setOpenIndex(openIndex === idx ? null : idx);
  };

  return (
    <section
      ref={ref}
      className="chapter faq-section"
      id="faq"
      data-visible={visible}
    >
      <div className="section-topline">
        <span className="eyebrow">05 / FREQUENTLY ASKED QUESTIONS</span>
        <span className="eyebrow">EVERYTHING ABOUT SWAR</span>
      </div>
      <h2>
        Built with clarity.
        <br />
        <em>Answers for the field.</em>
      </h2>
      <div className="faq-list">
        {faqs.map((faq, idx) => (
          <article
            key={idx}
            className={`faq-item ${openIndex === idx ? 'open' : ''}`}
          >
            <button
              type="button"
              className="faq-header"
              onClick={() => toggleFaq(idx)}
              aria-expanded={openIndex === idx}
            >
              <span className="faq-number">0{idx + 1}</span>
              <h3 className="faq-question">{faq.q}</h3>
              <span className="faq-tag">{faq.tag}</span>
              <span className="faq-toggle" aria-hidden="true">
                {openIndex === idx ? '−' : '+'}
              </span>
            </button>
            {openIndex === idx && (
              <div className="faq-body">
                <p>{faq.a}</p>
              </div>
            )}
          </article>
        ))}
      </div>
      <div className="faq-footer-note">
        <p>
          Have more questions or need field deployment documentation? Inspect the open-source architecture and protocol specifications.
        </p>
      </div>
    </section>
  );
}

function ClosingSection() {
  return (
    <section className="closing-section">
      <p className="eyebrow">EVERY VOICE DESERVES TO BE HEARD</p>
      <h2>
        When networks fail,
        <br />
        <em>keep speaking.</em>
      </h2>
      <div className="closing-actions">
        <a
          href={DEFAULT_APK_DOWNLOAD_URL}
          className="button button-primary"
          download={
            DEFAULT_APK_DOWNLOAD_URL.startsWith('/')
              ? 'swar-debug.apk'
              : undefined
          }
        >
          <ArrowDown size={16} /> Download Android APK
        </a>
        <a href="#demo" className="text-link">
          Try the walkie-talkie demo <ArrowUpRight size={15} />
        </a>
      </div>
    </section>
  );
}

function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="footer-upper">
        <a href="#home" className="brand">
          <BrandMark />
          <span>SWAR</span>
        </a>
        <p>
          Indian Multilingual Offline Voice Communication System over Wi-Fi
          Direct, Wi-Fi LAN &amp; Bluetooth Classic.
        </p>
        <a
          href={DEFAULT_APK_DOWNLOAD_URL}
          className="text-link"
          download={
            DEFAULT_APK_DOWNLOAD_URL.startsWith('/')
              ? 'swar-debug.apk'
              : undefined
          }
        >
          Download Android APK <ArrowDown size={14} />
        </a>
      </div>
      <div className="footer-lower">
        <span>© 2026 SWAR Project</span>
        <span>Zero infrastructure · 1.8 kbps semantic speech · Open protocol</span>
        <a href="#home">Back to top ↑</a>
      </div>
    </footer>
  );
}

export function Experience({ onSwitchView }: { onSwitchView?: (view: 'transceiver' | 'experience' | 'studio') => void } = {}) {
  const [paused, setPaused] = useState(false);
  const [reduced, setReduced] = useState(
    () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
  const [hidden, setHidden] = useState(document.hidden);

  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const update = () => setReduced(query.matches);
    const visibility = () => setHidden(document.hidden);
    query.addEventListener('change', update);
    document.addEventListener('visibilitychange', visibility);
    return () => {
      query.removeEventListener('change', update);
      document.removeEventListener('visibilitychange', visibility);
    };
  }, []);

  const motion = !paused && !reduced && !hidden;

  return (
    <MotionContext.Provider value={motion}>
      <div className="experience" data-motion={motion}>
        <a className="skip-link" href="#technology">
          Skip to content
        </a>
        <div className="announcement">
          <span>Every voice deserves to get through.</span>
          <a href="#mobile-app">
            Discover the Android App <ArrowRight size={13} />
          </a>
        </div>
        <Navigation onSwitchView={onSwitchView} />
        <main>
          <Hero />
          <TechnologySection />
          <DemoSection />
          <MobileAppSection />
          <LanguageSection />
          <FaqSection />
          <ClosingSection />
        </main>
        <SiteFooter />
        <button
          className="motion-control"
          disabled={reduced}
          aria-label={
            reduced
              ? 'System reduced motion enabled'
              : paused
                ? 'Resume ambient motion'
                : 'Pause ambient motion'
          }
          aria-pressed={paused || reduced}
          onClick={() => setPaused(!paused)}
        >
          {paused || reduced ? <Play size={12} /> : <Pause size={12} />}
          <span>
            {reduced
              ? 'REDUCED MOTION'
              : paused
                ? 'MOTION PAUSED'
                : 'PAUSE MOTION'}
          </span>
        </button>
      </div>
    </MotionContext.Provider>
  );
}
