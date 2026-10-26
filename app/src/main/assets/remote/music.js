// Track Record guest page, music for internet guests (SPEC v4; D-181). The host says what to play
// (view.listen): a Deezer preview, played here at the host's position and kept in step through the
// host's clock, or its stream of a local file. Wi-Fi guests never get it. Split from app.js (REVIEW.md
// C-05; D-274).

/**
 * The page's players. [listenNow]() is the view's listen info (or null), [soundOn]() whether the browser
 * may play sound yet, [transport]() the way to the host (for its clock).
 */
export function music({ listenNow, soundOn, transport }) {
  /** A hidden player in the page (findable when checking the page), playing what the host says. */
  function player(id) {
    const element = document.createElement("audio");
    element.id = id;
    element.hidden = true;
    document.body.appendChild(element);
    return element;
  }
  const previewAudio = player("music-preview");
  previewAudio.preload = "auto";
  const streamAudio = player("music-stream");
  streamAudio.autoplay = true;

  /** The music's volume on this device, 0–1 (D-185), remembered here. */
  function volume() {
    try {
      const v = Number(localStorage.getItem("hl_volume"));
      return localStorage.getItem("hl_volume") != null && v >= 0 && v <= 1 ? v : 1;
    } catch (e) {
      return 1;
    }
  }

  function setVolume(v) {
    previewAudio.volume = v;
    streamAudio.volume = v;
    try { localStorage.setItem("hl_volume", String(v)); } catch (e) { /* private mode */ }
  }
  setVolume(volume());
  /** Clock samples {offset, rtt, at}: host time ≈ Date.now() + offset, from the shortest round trip. */
  let clock = [];

  async function syncClock() {
    const t = transport();
    if (!t || !t.time || t.closed()) return;
    for (let i = 0; i < 5; i++) {
      const t0 = Date.now();
      try {
        const now = await t.time();
        const t1 = Date.now();
        clock.push({ offset: now - (t0 + t1) / 2, rtt: t1 - t0, at: t1 });
      } catch (e) {
        return;
      }
    }
    clock = clock.filter((c) => Date.now() - c.at < 60_000);
  }

  function hostNow() {
    const best = clock.reduce((a, b) => (!a || b.rtt < a.rtt ? b : a), null);
    return best ? Date.now() + best.offset : null;
  }

  /**
   * Follows the host's real signals only (feedback round 25; D-190): "playing from second x" or
   * "paused at second y". Each new signal is applied once (one seek, then play or pause); in between
   * the preview just plays, without the constant corrections that made it stutter.
   */
  let appliedSignal = "";
  function followMusic() {
    const listen = listenNow();
    if (!soundOn() || !listen) {
      if (!previewAudio.paused) previewAudio.pause();
      streamAudio.muted = true;
      appliedSignal = "";
      return;
    }
    if (listen.kind === "stream") {
      if (!previewAudio.paused) previewAudio.pause();
      streamAudio.muted = false;
      if (streamAudio.srcObject && streamAudio.paused) streamAudio.play().catch(() => {});
      appliedSignal = "";
      return;
    }
    streamAudio.muted = true;
    const signal = `${listen.url}|${listen.playing}|${listen.positionMs}|${listen.atMs}`;
    if (signal === appliedSignal) return;
    if (listen.url && previewAudio.dataset.src !== listen.url) {
      previewAudio.dataset.src = listen.url;
      previewAudio.src = listen.url;
    }
    if (previewAudio.readyState < 1) return; // seek once the preview's length is known
    const now = hostNow();
    if (now == null) { syncClock(); return; }
    appliedSignal = signal;
    const target = listen.playing ? listen.positionMs + (now - listen.atMs) : listen.positionMs;
    previewAudio.playbackRate = 1;
    previewAudio.currentTime = Math.max(0, target / 1000);
    if (listen.playing) previewAudio.play().catch(() => {});
    else previewAudio.pause();
  }
  setInterval(followMusic, 250);

  return {
    volume,
    setVolume,
    syncClock,
    follow: followMusic,
    /** Starts the stream player inside the tap that allows sound. */
    unlock: () => streamAudio.play().catch(() => {}),
    /** The host's stream of a local file arrived. */
    setStream(stream) {
      streamAudio.srcObject = stream;
      followMusic();
    },
  };
}
