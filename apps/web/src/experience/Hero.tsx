import { useContext, useEffect, useRef, useState } from "react";
import { ArrowDown, ArrowUpRight } from "lucide-react";
import { MotionContext, useInView, WaveBars } from "./primitives";

const phrases = [
  "Can you hear me?",
  "हमें मदद चाहिए।",
  "Meet me at the main gate.",
  "रास्ता साफ़ है, आगे बढ़ें।",
  "Signal is weak, hold position.",
  "संदेश प्राप्त हुआ।",
];

function SpeechEnvironment({ active }: { active: boolean }) {
  const motion = useContext(MotionContext);
  const svgRef = useRef<SVGSVGElement>(null);
  useEffect(() => {
    if (motion) svgRef.current?.unpauseAnimations();
    else svgRef.current?.pauseAnimations();
  }, [motion]);
  const incoming =
    "Can you hear me?  →  हमें मदद चाहिए  →  VOICE  →  आवाज़  →  TEXT  →  SEMANTIC PACKET  →  SIGNAL  →  नेटवर्क नहीं है  →  RELAY  →  ";
  const outgoing =
    "संदेश प्राप्त हुआ  →  Meet me at the main gate.  →  सहायता रास्ते में है  →  Every voice finds a way  →  सुरक्षित स्थान पर पहुँचें  →  Packet received  →  ";
  return (
    <div
      className={`speech-environment ${active ? "is-listening" : ""}`}
      aria-hidden="true"
    >
      <svg
        ref={svgRef}
        className="speech-paths"
        viewBox="0 0 1440 820"
        preserveAspectRatio="none"
      >
        <defs>
          <path
            id="wandering-voice"
            d="M -650,380 C -220,440 160,600 305,450 C 470,300 160,200 155,380 C 150,530 430,625 720,625"
          />
          <path
            id="received-voice"
            d="M 720,625 C 940,625 1200,560 1670,510"
          />
        </defs>
        <text
          className="incoming-text"
          dominantBaseline="central"
          textLength="4800"
          lengthAdjust="spacingAndGlyphs"
        >
          <textPath href="#wandering-voice" startOffset="-600">
            {incoming.repeat(6)}
            <animate
              attributeName="startOffset"
              from="-600"
              to="0"
              dur="20s"
              repeatCount="indefinite"
            />
          </textPath>
        </text>
        <use href="#received-voice" className="transcription-ribbon" />
        <text
          className="outgoing-text"
          dominantBaseline="central"
          dy="0"
          textLength="2400"
          lengthAdjust="spacingAndGlyphs"
        >
          <textPath href="#received-voice" startOffset="-600">
            {outgoing.repeat(4)}
            <animate
              attributeName="startOffset"
              from="-600"
              to="0"
              dur="13s"
              repeatCount="indefinite"
            />
          </textPath>
        </text>
      </svg>
      <div className="fragment fragment-one">
        <span>“Signal is weak.”</span>
        <small>INCOMING / 01</small>
      </div>
      <div className="fragment fragment-two">
        <span>“Can you hear me?”</span>
        <small>VOICE / EN</small>
      </div>
      <div className="fragment fragment-three">
        <span>“हमें मदद चाहिए।”</span>
        <small>EMERGENCY / HI</small>
      </div>
    </div>
  );
}

export function Hero() {
  const { ref, visible } = useInView<HTMLElement>();
  const motion = useContext(MotionContext);
  const [hovered, setHovered] = useState(false);
  const [listening, setListening] = useState(false);
  const [phrase, setPhrase] = useState(0);
  const active = hovered || listening;
  useEffect(() => {
    if (!visible || !motion) return;
    const timer = window.setInterval(
      () => setPhrase((p) => (p + 1) % phrases.length),
      active ? 1400 : 2800,
    );
    return () => window.clearInterval(timer);
  }, [active, visible, motion]);
  useEffect(() => {
    if (!motion || !visible) return;
    let frame = 0;
    const scroll = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        ref.current?.style.setProperty(
          "--hero-drift",
          `${Math.min(window.scrollY * 0.07, 35)}px`,
        );
        frame = 0;
      });
    };
    window.addEventListener("scroll", scroll, { passive: true });
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("scroll", scroll);
      ref.current?.style.setProperty("--hero-drift", "0px");
    };
  }, [motion, visible, ref]);
  return (
    <section
      ref={ref}
      className="hero"
      id="home"
      data-running={visible}
      onPointerMove={(event) => {
        if (!motion || event.pointerType !== "mouse") return;
        const bounds = event.currentTarget.getBoundingClientRect();
        event.currentTarget.style.setProperty(
          "--cursor-x",
          `${(event.clientX / bounds.width - 0.5) * -22}px`,
        );
        event.currentTarget.style.setProperty(
          "--cursor-y",
          `${(event.clientY / bounds.height - 0.5) * -14}px`,
        );
      }}
      onPointerLeave={(event) => {
        event.currentTarget.style.setProperty("--cursor-x", "0px");
        event.currentTarget.style.setProperty("--cursor-y", "0px");
      }}
    >
      <MotionContext.Provider value={motion && visible}>
        <SpeechEnvironment active={active} />
      </MotionContext.Provider>
      <div className="hero-copy">
        <p className="eyebrow hero-eyebrow">
          <span className="status-dot" /> SWAR — OFFLINE AI VOICE & MESH
        </p>
        <h1>
          When networks fail,
          <br />
          <em>keep speaking.</em>
        </h1>
        <p className="hero-description">
          AI-powered multilingual voice bridge using 1.8 kbps semantic speech tokens
          <br className="desktop-break" /> over Wi-Fi Direct and Bluetooth mesh.
        </p>
        <div className="hero-actions">
          <a href="#demo" className="button button-primary">
            Try the walkie-talkie <ArrowUpRight size={17} />
          </a>
          <a href="#mobile-app" className="text-link">
            See Android app in action <ArrowDown size={15} />
          </a>
        </div>
        <p className="hero-footnote">
          Zero internet. Zero cellular towers. 98% bandwidth reduction.
        </p>
      </div>
      <div className="hero-audio">
        <div className={`audio-caption ${active ? "active" : ""}`}>
          <span className="status-dot" />
          {active
            ? "LISTENING · ILLUSTRATIVE"
            : "A LITTLE SIGNAL. A LOT OF MEANING."}
        </div>
        <p key={phrase} className="live-phrase">
          “{phrases[phrase]}”
        </p>
        <button
          className="wave-capsule"
          onMouseEnter={() => setHovered(true)}
          onMouseLeave={() => setHovered(false)}
          onFocus={() => setHovered(true)}
          onBlur={() => setHovered(false)}
          onClick={() => setListening(!listening)}
          aria-pressed={listening}
          aria-label="Toggle illustrative listening waveform"
        >
          <WaveBars active={active} />
        </button>
      </div>
      <div className="hero-bottom">
        <span>BUILT AROUND THE HUMAN VOICE</span>
        <a href="#technology">
          SCROLL TO DISCOVER <ArrowDown size={12} />
        </a>
        <span>INDIA, IN EVERY LANGUAGE</span>
      </div>
    </section>
  );
}

