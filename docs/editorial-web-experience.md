# SWAR editorial web experience

Run `npm run dev:web` from the repository root. Build with `npm run build:web`
(TypeScript checking followed by Vite's production build).

The public experience lives at `/`. The existing backend-connected prototype
is lazy-loaded at `/?view=studio#studio`; incoming `/#studio` and `/#receiver`
bookmarks also open it. Its components, device tools, recording utilities,
history, API calls, and WebSocket handling are retained. It still requires the
existing backend. Its original styles are isolated in a separate lazy CSS chunk.

## Reference study

The supplied recording is approximately 7.2 seconds, 216 frames, 1728 × 1080.
All frames were extracted into contact sheets for inspection before implementation.
The visible reference stays on its hero: a centered serif headline, restrained
navigation, cream background, a loose grey transcription loop entering from the
left, and an outlined waveform feeding a dark text ribbon towards the right.
A small teal processing status appears and disappears while the text travels.
There is no lower-page scrolling in the recording; the subsequent SWAR
sections extend this visual language using the user's written requirements.
No reference branding, copy, images, or proprietary assets are included.

## Implementation

- React 19 and TypeScript use the existing Vite setup, without new runtime dependencies.
- `src/experience/Experience.tsx` contains the public page, navigation, waveform,
  scroll stages, offline illustration, language selector, and push-to-talk component.
- `src/index.css` contains the new responsive design and motion system.
- Instrument Serif and DM Sans are served locally, with their OFL licenses in
  `public/fonts`. Indian scripts use the device's installed script fonts.
- SVG text paths carry repeating speech and packet fragments; CSS animates
  waveform transforms and packet movement. Pointer movement gently offsets
  peripheral fragments. A requestAnimationFrame-throttled scroll listener shifts
  the hero, while IntersectionObserver controls stage activation and visibility.
- Ambient motion pauses outside the viewport, when the document is hidden, when
  manually paused, and for `prefers-reduced-motion`. Language autoplay stops after
  an explicit language selection. Event listeners and timers clean up on unmount.

## Demo boundary

The public push-to-talk interaction uses selectable sample messages. Holding the
button starts its listening state; releasing progresses through understanding,
transmitting, and receipt. Space and Enter work too. A six-second maximum hold,
pointer cancellation, lost capture, blur, and unmount are handled. The component
never requests microphone permission or claims to connect devices.

`PushToTalk` accepts `onTransmit(message)` as a future mobile integration seam.
Actual microphone capture and transport belong in that integration; the marketing
simulation and existing legacy web studio are explicitly labeled as prototypes.
No measured speed, compression, reliability, or language-coverage claims are added.

## Verification

- Production build and strict TypeScript compilation.
- Browser checks of the desktop and mobile layouts, navigation, waveform toggle,
  motion pause/resume, language selection, research disclosures, and sample receipt.
- Mouse and keyboard sample transmissions, including a non-default message.
- Local fonts; no external background video or animation library on the public page.
- Responsive widths checked: 320, 390, 768, and 1440 pixels. No horizontal overflow
  was found in the main content, headings, navigation, or controls.
- A 180-frame requestAnimationFrame sample in the local production preview measured
  a 6.50 ms mean interval and 12.10 ms 95th percentile, with no intervals over
  34 ms. This is a short browser scheduling sample on the development machine,
  not a physical-device benchmark or proof that every frame was painted.
- The production landing page loaded only its own JS/CSS and three local fonts;
  the legacy studio chunk and backend requests were deferred until its route opened.

The animation design favors transform changes and pauses idle work. A measured
60 FPS on physical mobile devices is not asserted; hardware profiling and real
device communication remain separate validation work.
