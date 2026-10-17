# Swar — Turn Barriers into Shared Experiences

**Swar** is an accessibility-first communication bridge designed to connect two disconnected sides across **technical expertise**, **physical ability**, **language**, and **digital/physical environments**.

---

## 🎨 Design System & Visual Fidelity
Crafted to precisely match the visual language shown in the design references:
- **Palette**: Warm Cream (`#f4f0e6`), Terracotta / Clay (`#b85d38`), Deep Olive Green (`#26352b`), Light Olive / Sage (`#bac1a7`), and Ink (`#1b221d`).
- **Typography**: `DM Serif Display` for elegant headlines and `Plus Jakarta Sans` for clean, highly legible interface copy.
- **Micro-details**: Floating pill badges, organic pulsing blobs, tactile haptic feedback indicators, and rounded phone hardware frames.

---

## ✨ Implemented Core Animations & Interactivity

### 1. Two Sides One Shared Space Animation (Hero Canvas)
- **Side A (Terracotta Blob)** and **Side B (Olive Blob)** morph organically using cubic-bezier wave keyframes.
- **Connecting Bridge / Arch**: An SVG conduit with flowing communication energy particles traveling back and forth between both perspectives.
- **Harmonic Audio Bridge**: Clicking either side or the *"Two sides. One shared space."* pill emits a gentle harmonic resonance (Web Audio API) and triggers a synchronized ripple wave across the bridge.

### 2. Mobile Screens Sliding & Tumble Animation
- **Right-to-Left Slide & Tumble Entrance**: The 3 mobile screens slide from the right (`120vw`), tumble through 3D space (`rotateZ`, `rotateY`, `scale`), and settle dynamically with realistic momentum into their fanned 3D layout:
  - **Left Phone (Live Translation)**: Tilted left (`-7deg`), displaying English-to-Hindi speech translation with live audio waveform bars.
  - **Center Phone (Soundscape Live)**: Elevated center hero (`0deg`, user's provided component code), featuring the organic sound orb, live caption stream, and live indicator pill.
  - **Right Phone (Quick Connect)**: Tilted right (`+7deg`), showcasing quick-assist modality cards (*Visual assist*, *Voice assist*, *Language assist*).
- **Interactive Replay Button**: A floating showcase controller and on-page button allow instant replay of the slide-and-tumble cascade anytime.
- **Screen Selector Tabs**: Clicking between *Soundscape Live*, *Live Translation*, and *Quick Connect* elevates and centers each phone into the focal view.

### 3. Interactive Soundscape Simulation
- Tapping the **Sound Orb** in the center screen simulates nearby conversation detection, pulses with organic haptic ripples, and rotates through live situational captions (transit announcements, arrival alerts, environmental alerts).

### 4. Interactive "How Swar Works" Concept Interface
- Selecting step **01 (Translate)**, **02 (Adapt)**, or **03 (Connect)** updates the dual-side concept board with live examples (e.g. Spoken English ↔ Hindi Speech, Complex Medical Jargon ↔ Intuitive Plain Meaning, Environmental Audio ↔ Visual/Haptic Pulse).

### 5. Accessibility & Responsive Support
- Fully responsive across desktop, tablet, and mobile screen sizes.
- Keyboard navigation support (`tabindex="0"`, `role="button"`, ARIA expanded states for the FAQ accordion).

---

## 🚀 How to View Locally
Simply double-click or open [`index.html`](file:///d:/devlympics/index.html) in any modern web browser (Chrome, Edge, Firefox, Safari). No node dependencies or build steps required.
