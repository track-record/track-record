// Track Record guest page, music on the guest's phone (SPEC v4; D-181). The host says what to play
// (view.listen): a Deezer preview, played here at the host's position and kept in step through the
// host's clock, or a local file: the room's stream over the internet, or on Wi-Fi the file from the host's
// server, played like a preview. Wi-Fi guests turn it on themselves. Split from app.js
// (REVIEW.md C-05; D-274).

/**
 * The page's players. [listenNow]() is the view's listen info (or null), [soundOn]() whether sound is wanted and the
 * browser may play it yet, [transport]() the way to the host (for its clock), [fileAddress](listen) where a local
 * file can be loaded from (Wi-Fi), or null where it comes as a stream. [onFailed]() is told when this browser can't
 * play the host's file.
 */
export function music({ listenNow, soundOn, transport, fileAddress, onFailed = () => {} }) {
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
  /**
   * The host's file this browser couldn't decode (Opus or FLAC on older iPhones, ALAC anywhere), so the page can say why
   * it stays quiet instead of waiting for it for ever (REVIEW-3 B-25).
   */
  let failedSrc = null;
  previewAudio.addEventListener("error", () => {
    const listen = listenNow();
    if (!listen || listen.url || !previewAudio.dataset.src) return; // a Deezer preview: the host shows its errors
    failedSrc = previewAudio.dataset.src;
    onFailed();
  });
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
  /** Whether the page can set the volume: iPhones and iPads leave it to their buttons, and read it as 1 (D-388). */
  const volumeAdjustable = (() => {
    const probe = document.createElement("audio");
    probe.volume = 0.5;
    return probe.volume === 0.5;
  })();
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
   * Follows the host's real signals only (D-190): "playing from second x" or
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
    const url = listen.url || fileAddress(listen);
    if (!url) {
      if (!previewAudio.paused) previewAudio.pause();
      streamAudio.muted = false;
      if (streamAudio.srcObject && streamAudio.paused) streamAudio.play().catch(() => {});
      appliedSignal = "";
      return;
    }
    streamAudio.muted = true;
    const signal = `${url}|${listen.playing}|${listen.positionMs}|${listen.atMs}`;
    if (signal === appliedSignal) return;
    if (previewAudio.dataset.src !== url) {
      previewAudio.dataset.src = url;
      previewAudio.src = url;
    }
    if (previewAudio.readyState < 1) return; // seek once the preview's length is known
    const now = hostNow();
    if (now == null) { syncClock(); return; }
    appliedSignal = signal;
    const target = listen.playing ? listen.positionMs + (now - listen.atMs) : listen.positionMs;
    previewAudio.playbackRate = 1;
    previewAudio.currentTime = Math.max(0, target / 1000);
    previewAudio.muted = false;
    if (listen.playing) previewAudio.play().catch(() => {});
    else previewAudio.pause();
  }
  setInterval(followMusic, 250);

  return {
    volume,
    setVolume,
    volumeAdjustable,
    syncClock,
    follow: followMusic,
    /** Whether the song playing now is a file this browser can't play, with the sound on. */
    cantPlay: () => soundOn() && failedSrc != null && previewAudio.dataset.src === failedSrc,
    /**
     * Starts the players inside the tap that allows sound: some browsers let a player start later only if it started
     * in a tap. Silent until the host's position is applied; when it already is (sound turned on again mid-song, just
     * applied by [follow] in this tap), it plays as it is: muting it again kept it silent.
     */
    unlock() {
      streamAudio.play().catch(() => {});
      if (previewAudio.src && !appliedSignal) {
        previewAudio.muted = true;
        previewAudio.play().catch(() => {});
      }
    },
    /** The host's stream of a local file arrived. */
    setStream(stream) {
      streamAudio.srcObject = stream;
      followMusic();
    },
  };
}
