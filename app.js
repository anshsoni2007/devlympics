/**
 * SWAR - Interactive Frontend Engine
 * Connects disconnected sides with dynamic animations, telephone ring entry, and accessibility-first controls.
 */

document.addEventListener('DOMContentLoaded', () => {
  // 1. SELECTORS & STATE
  const phonesStage = document.getElementById('phones-stage');
  const replayBtnSection = document.getElementById('replay-anim-action');
  const soundOrb = document.getElementById('sound-orb-interactive');
  const liveCaption = document.getElementById('live-caption-stream');
  const sharedSpaceCard = document.getElementById('shared-space-card');
  const sharedSpacePill = document.getElementById('shared-space-pill');
  const blobA = document.getElementById('side-blob-a');
  const blobB = document.getElementById('side-blob-b');
  const navbar = document.getElementById('main-nav');
  const faqItems = document.querySelectorAll('.faq-item');
  const screenTabs = document.querySelectorAll('.screen-tab');
  const stepCards = document.querySelectorAll('.step-card');
  const apkModal = document.getElementById('apk-modal');
  const apkDownloadLinks = document.querySelectorAll('a[href="#download"], a[href="#apk-download-modal"], .download-card-pill');
  const modalClose = document.getElementById('modal-close');
  const simDownloadBtn = document.getElementById('sim-download-btn');
  const toastClose = document.getElementById('close-toast');
  const footerToast = document.getElementById('footer-toast');
  const thumbsUp = document.querySelector('.toast-thumbs-up');
  const thumbsDown = document.querySelector('.toast-thumbs-down');

  // Audio Context (Synthesized Vintage Telephone & Harmonic Bridge)
  let audioCtx = null;
  let hasTelephoneRang = false;

  function getAudioContext() {
    if (!audioCtx) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (AudioContextClass) {
        audioCtx = new AudioContextClass();
      }
    }
    if (audioCtx && audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
    return audioCtx;
  }

  // 2. SYNTHESIZED TELEPHONE BELL RING
  // Classic dual-tone (440Hz + 480Hz) modulated with a 20Hz bell-clapper oscillation
  function playTelephoneRingBurst(startTime, duration = 0.8) {
    const ctx = getAudioContext();
    if (!ctx) return;

    try {
      const now = startTime || ctx.currentTime;

      // 440Hz and 480Hz dual tones
      const osc1 = ctx.createOscillator();
      const osc2 = ctx.createOscillator();
      osc1.type = 'sine';
      osc2.type = 'sine';
      osc1.frequency.setValueAtTime(440, now);
      osc2.frequency.setValueAtTime(480, now);

      // 20Hz tremolo / clapper modulation
      const mod = ctx.createOscillator();
      const modGain = ctx.createGain();
      mod.type = 'square';
      mod.frequency.setValueAtTime(20, now);
      modGain.gain.setValueAtTime(0.5, now);
      mod.connect(modGain);

      // Master burst gain envelope
      const burstGain = ctx.createGain();
      burstGain.gain.setValueAtTime(0, now);
      burstGain.gain.linearRampToValueAtTime(0.12, now + 0.04);
      burstGain.gain.setValueAtTime(0.12, now + duration - 0.05);
      burstGain.gain.linearRampToValueAtTime(0.0001, now + duration);

      osc1.connect(burstGain);
      osc2.connect(burstGain);
      burstGain.connect(ctx.destination);

      osc1.start(now);
      osc2.start(now);
      osc1.stop(now + duration);
      osc2.stop(now + duration);
    } catch (e) {
      console.warn('Audio ring note fallback:', e);
    }
  }

  function playHarmonicTone(freq = 440, type = 'sine', duration = 0.4) {
    const ctx = getAudioContext();
    if (!ctx) return;
    try {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = type;
      osc.frequency.setValueAtTime(freq, ctx.currentTime);
      gain.gain.setValueAtTime(0.08, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + duration);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start();
      osc.stop(ctx.currentTime + duration);
    } catch (e) {
      console.warn('Audio note fallback:', e);
    }
  }

  // TELEPHONE RING SEQUENCE UPON ENTERING THE WEBSITE
  function startTelephoneEntryRing() {
    // 1. Visual Ringing State
    if (blobA) blobA.classList.add('is-ringing');
    if (blobB) blobB.classList.add('is-ringing');
    if (sharedSpacePill) {
      sharedSpacePill.classList.add('ringing-badge');
      sharedSpacePill.innerHTML = '<span class="pulse-dot"></span><span>Connecting call... 📞</span>';
    }

    // 2. Audio Ring: Ring 1 (0.8s) + Pause (0.35s) + Ring 2 (0.8s)
    const ctx = getAudioContext();
    if (ctx && ctx.state === 'running') {
      const t = ctx.currentTime + 0.05;
      playTelephoneRingBurst(t, 0.75);
      playTelephoneRingBurst(t + 1.1, 0.75);
    }

    // 3. Transition back to Calm Idle Morphing Animation after 2.4s
    setTimeout(() => {
      if (blobA) blobA.classList.remove('is-ringing');
      if (blobB) blobB.classList.remove('is-ringing');
      if (sharedSpacePill) {
        sharedSpacePill.classList.remove('ringing-badge');
        sharedSpacePill.innerHTML = '<span class="pulse-dot"></span><span>Two sides. One shared space.</span>';
      }
    }, 2400);
  }

  // Trigger on site entry
  startTelephoneEntryRing();

  // Browser Autoplay Policy helper: if audio was suspended on entry, play ring on first user gesture
  function handleFirstUserEntryGesture() {
    if (hasTelephoneRang) return;
    hasTelephoneRang = true;
    const ctx = getAudioContext();
    if (ctx) {
      ctx.resume().then(() => {
        startTelephoneEntryRing();
      });
    }
    // Remove one-time entry listeners
    ['click', 'pointerdown', 'scroll', 'keydown', 'touchstart'].forEach((evt) => {
      window.removeEventListener(evt, handleFirstUserEntryGesture);
    });
  }

  ['click', 'pointerdown', 'scroll', 'keydown', 'touchstart'].forEach((evt) => {
    window.addEventListener(evt, handleFirstUserEntryGesture, { once: true, passive: true });
  });

  // 3. SLOWED SLIDE FROM RIGHT TO LEFT & TUMBLE ANIMATION TRIGGER
  function triggerSlideAndTumble() {
    if (!phonesStage) return;

    // Reset animation state
    phonesStage.classList.remove('is-animating');
    void phonesStage.offsetWidth; // Force CSS reflow

    // Apply animation class (configured to 2.8s-3.1s in styles.css)
    phonesStage.classList.add('is-animating');

    // Chime sound on entrance
    playHarmonicTone(523.25, 'triangle', 0.8); // C5
    setTimeout(() => playHarmonicTone(659.25, 'triangle', 0.8), 350); // E5
    setTimeout(() => playHarmonicTone(783.99, 'triangle', 1.0), 700); // G5
  }

  if (replayBtnSection) {
    replayBtnSection.addEventListener('click', (e) => {
      e.preventDefault();
      triggerSlideAndTumble();
    });
  }

  // Intersection Observer to trigger slow slide & tumble when scrolled into view
  let hasTriggeredOnScroll = false;
  if ('IntersectionObserver' in window && phonesStage) {
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting && !hasTriggeredOnScroll) {
          hasTriggeredOnScroll = true;
          triggerSlideAndTumble();
        }
      });
    }, { threshold: 0.25 });

    observer.observe(phonesStage);
  } else {
    triggerSlideAndTumble();
  }

  // 4. TWO SIDES ONE SHARED SPACE INTERACTIVE ELEMENT
  function triggerBridgePulse() {
    startTelephoneEntryRing();
    playHarmonicTone(349.23, 'sine', 0.5); // F4
    setTimeout(() => playHarmonicTone(440, 'sine', 0.6), 200); // A4
    setTimeout(() => playHarmonicTone(523.25, 'sine', 0.7), 400); // C5
  }

  if (sharedSpacePill) {
    sharedSpacePill.addEventListener('click', triggerBridgePulse);
  }
  if (blobA) blobA.addEventListener('click', triggerBridgePulse);
  if (blobB) blobB.addEventListener('click', triggerBridgePulse);

  // 5. SOUNDSCAPE SCREEN SIMULATION (From User's Code)
  const soundscapeCaptions = [
    "“Your bus will arrive at platform three in five minutes.”",
    "“Attention passengers: Doors closing on the right side.”",
    "“Dr. Sharma: Appointment room three is ready for you.”",
    "“A bicycle bell rang 3 meters behind you.”",
    "“Nearby speaker: The presentation will start in two minutes.”"
  ];
  let captionIndex = 0;

  if (soundOrb) {
    soundOrb.addEventListener('click', () => {
      // Rotate caption
      captionIndex = (captionIndex + 1) % soundscapeCaptions.length;
      if (liveCaption) {
        liveCaption.style.opacity = '0';
        setTimeout(() => {
          liveCaption.textContent = soundscapeCaptions[captionIndex];
          liveCaption.style.opacity = '1';
        }, 180);
      }

      // Visual pulse
      soundOrb.style.transform = 'scale(1.15)';
      setTimeout(() => { soundOrb.style.transform = ''; }, 220);

      // Play soft speech detect chime
      playHarmonicTone(600, 'triangle', 0.35);
    });
  }

  // 6. PHONE SCREEN SELECTOR TABS
  const phoneLeft = document.getElementById('phone-1');
  const phoneCenter = document.getElementById('phone-2');
  const phoneRight = document.getElementById('phone-3');

  screenTabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      screenTabs.forEach((t) => {
        t.classList.remove('active');
        t.setAttribute('aria-selected', 'false');
      });
      tab.classList.add('active');
      tab.setAttribute('aria-selected', 'true');

      const target = tab.dataset.target;
      if (phoneLeft) phoneLeft.style.zIndex = '2';
      if (phoneCenter) phoneCenter.style.zIndex = '2';
      if (phoneRight) phoneRight.style.zIndex = '2';

      if (target === 'screen-olive' && phoneCenter) {
        phoneCenter.style.zIndex = '15';
        phoneCenter.style.transform = 'perspective(1200px) translateY(-24px) scale(1.08)';
        if (phoneLeft) phoneLeft.style.transform = 'perspective(1200px) rotate(-7deg) translateY(18px) translateX(30px) scale(0.92)';
        if (phoneRight) phoneRight.style.transform = 'perspective(1200px) rotate(7deg) translateY(18px) translateX(-30px) scale(0.92)';
      } else if (target === 'screen-cream' && phoneLeft) {
        phoneLeft.style.zIndex = '15';
        phoneLeft.style.transform = 'perspective(1200px) rotate(0deg) translateY(-24px) scale(1.08)';
        if (phoneCenter) phoneCenter.style.transform = 'perspective(1200px) rotate(7deg) translateY(18px) translateX(20px) scale(0.92)';
        if (phoneRight) phoneRight.style.transform = 'perspective(1200px) rotate(12deg) translateY(24px) translateX(-10px) scale(0.88)';
      } else if (target === 'screen-clay' && phoneRight) {
        phoneRight.style.zIndex = '15';
        phoneRight.style.transform = 'perspective(1200px) rotate(0deg) translateY(-24px) scale(1.08)';
        if (phoneCenter) phoneCenter.style.transform = 'perspective(1200px) rotate(-7deg) translateY(18px) translateX(-20px) scale(0.92)';
        if (phoneLeft) phoneLeft.style.transform = 'perspective(1200px) rotate(-12deg) translateY(24px) translateX(10px) scale(0.88)';
      }
    });
  });

  // 7. HOW SWAR WORKS INTERACTIVE STEP CARDS
  const sideATag = document.getElementById('side-a-tag');
  const sideBTag = document.getElementById('side-b-tag');
  const sideAContent = document.getElementById('side-a-content');
  const sideBContent = document.getElementById('side-b-content');

  const stepModes = {
    1: {
      aTag: 'Spoken English',
      aQuote: '“Your bus will arrive at platform three in five minutes.”',
      bTag: 'Live Hindi Feed',
      bQuote: '“आपकी बस 5 मिनट में प्लेटफॉर्म 3 पर पहुंचेगी।”',
      bSub: 'Haptic wave confirmed'
    },
    2: {
      aTag: 'Complex Medical Jargon',
      aQuote: '“Transient vasovagal syncope exacerbated by orthostatic stress.”',
      bTag: 'Intuitive Plain Meaning',
      bQuote: '“You got dizzy and fainted because you stood up too quickly while dehydrated.”',
      bSub: 'Visual summary chart ready'
    },
    3: {
      aTag: 'Physical Room Audio',
      aQuote: '“Doorbell chime sounded twice on the front porch.”',
      bTag: 'Ambient Smart Visual',
      bQuote: '“Doorbell: Someone is at your front entrance right now.”',
      bSub: 'Vibration pattern: Pulse x 2'
    }
  };

  stepCards.forEach((card) => {
    card.addEventListener('click', () => {
      stepCards.forEach((c) => c.classList.remove('active'));
      card.classList.add('active');

      const stepNum = card.dataset.step;
      const data = stepModes[stepNum];

      if (data && sideATag && sideBTag) {
        sideATag.textContent = data.aTag;
        sideBTag.textContent = data.bTag;

        sideAContent.querySelector('.pane-quote').textContent = data.aQuote;
        sideBContent.querySelector('.pane-quote-hindi').textContent = data.bQuote;
        sideBContent.querySelector('.haptic-pill span').textContent = data.bSub;

        playHarmonicTone(400 + (parseInt(stepNum, 10) * 80), 'sine', 0.25);
      }
    });
  });

  // 8. FAQ ACCORDION INTERACTIVITY
  faqItems.forEach((item) => {
    const trigger = item.querySelector('.faq-trigger');
    const content = item.querySelector('.faq-content');

    trigger.addEventListener('click', () => {
      const isOpen = item.classList.contains('open');

      faqItems.forEach((other) => {
        other.classList.remove('open');
        other.querySelector('.faq-trigger').setAttribute('aria-expanded', 'false');
        other.querySelector('.faq-content').hidden = true;
      });

      if (!isOpen) {
        item.classList.add('open');
        trigger.setAttribute('aria-expanded', 'true');
        content.hidden = false;
      }
    });
  });

  // 9. NAVBAR SCROLL EFFECT
  window.addEventListener('scroll', () => {
    if (window.scrollY > 40) {
      navbar.classList.add('scrolled');
    } else {
      navbar.classList.remove('scrolled');
    }
  });

  // 10. APK PLACEHOLDER MODAL
  apkDownloadLinks.forEach((link) => {
    link.addEventListener('click', (e) => {
      e.preventDefault();
      if (apkModal) {
        apkModal.showModal();
        playHarmonicTone(587.33, 'triangle', 0.3); // D5
      }
    });
  });

  if (modalClose && apkModal) {
    modalClose.addEventListener('click', () => apkModal.close());
  }

  if (apkModal) {
    apkModal.addEventListener('click', (e) => {
      if (e.target === apkModal) apkModal.close();
    });
  }

  if (simDownloadBtn) {
    simDownloadBtn.addEventListener('click', () => {
      simDownloadBtn.textContent = 'Preparing Swar APK bundle... (Simulated)';
      simDownloadBtn.style.background = 'var(--clay)';
      playHarmonicTone(659.25, 'sine', 0.4);

      setTimeout(() => {
        simDownloadBtn.textContent = '✓ Swar Companion APK Ready (Demo)';
        simDownloadBtn.style.background = 'var(--olive)';
      }, 1200);
    });
  }

  // 11. TOAST NOTIFICATION ACTIONS
  if (toastClose && footerToast) {
    toastClose.addEventListener('click', () => {
      footerToast.style.transition = 'opacity 0.3s ease, transform 0.3s ease';
      footerToast.style.opacity = '0';
      footerToast.style.transform = 'translate(-50%, 20px)';
      setTimeout(() => footerToast.remove(), 300);
    });
  }

  if (thumbsUp) {
    thumbsUp.addEventListener('click', () => {
      thumbsUp.style.transform = 'scale(1.3)';
      setTimeout(() => { thumbsUp.style.transform = ''; }, 300);
      playHarmonicTone(784, 'triangle', 0.4);
    });
  }

  if (thumbsDown) {
    thumbsDown.addEventListener('click', () => {
      thumbsDown.style.transform = 'scale(1.3)';
      setTimeout(() => { thumbsDown.style.transform = ''; }, 300);
    });
  }
});
