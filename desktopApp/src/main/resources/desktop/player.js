// The host's music player on desktop (PLATFORMS.md, the AudioEngine seam): one <audio> element driven by the app
// through bridge.js. It plays a clip window of the file (start offset and clip limit, as Media3's clipping does on
// Android): positions it reports are within the window, and it ends at the window's end.
// Commands: load {src, startMs, endMs, play, positionMs}, play, pause, seek {ms}, window {endMs, positionMs, play},
// stop, volume {v}. The state goes out on every change and every 250 ms while playing.
let port = null;
const audio = new Audio();
audio.id = "player-audio";
audio.preload = "auto";
document.body.appendChild(audio);

/** The clip window in file milliseconds; endMs null: to the end of the file. */
let startMs = 0;
let endMs = null;
/** The window's end was reached (the clip "ended", as Media3 reports it). */
let clipEnded = false;
let loaded = false;
let ticker = 0;

function toApp(message) {
  if (port) port.postMessage(JSON.stringify(message));
}

function fileMs() {
  return Math.round(audio.currentTime * 1000);
}

function durationMs() {
  return Number.isFinite(audio.duration) ? Math.round(audio.duration * 1000) : 0;
}

function state() {
  const end = endMs ?? durationMs();
  toApp({
    t: "state",
    loaded,
    playing: !audio.paused && !audio.ended && !clipEnded,
    ended: clipEnded || audio.ended,
    positionMs: Math.max(0, Math.min(fileMs(), end || Infinity) - startMs),
    clipDurationMs: end > 0 ? Math.max(0, end - startMs) : 0,
    error: !!audio.error,
  });
}

/** Stops at the window's end; checked often while playing, since timeupdate comes only every 250 ms or so. */
function watchEnd() {
  if (endMs != null && !clipEnded && fileMs() >= endMs) {
    audio.pause();
    audio.currentTime = endMs / 1000;
    clipEnded = true;
  }
  state();
}

function startTicker() {
  clearInterval(ticker);
  ticker = setInterval(() => {
    if (audio.paused) { clearInterval(ticker); return; }
    watchEnd();
  }, 50);
}

for (const event of ["loadedmetadata", "playing", "pause", "seeked", "ended", "error", "durationchange", "waiting"]) {
  audio.addEventListener(event, () => {
    if (event === "playing") startTicker();
    state();
  });
}

function play() {
  // Played again after the clip's end: from its start, as Android's player does.
  if (clipEnded || audio.ended) {
    clipEnded = false;
    audio.currentTime = startMs / 1000;
  }
  audio.play().catch((e) => toApp({ t: "log", text: `play: ${e}` }));
}

function run(c) {
  switch (c.t) {
    case "load":
      startMs = c.startMs || 0;
      endMs = c.endMs ?? null;
      clipEnded = false;
      loaded = true;
      audio.src = c.src;
      audio.currentTime = (startMs + (c.positionMs || 0)) / 1000;
      if (c.play) play(); else audio.pause();
      state();
      break;
    case "window": {
      // A new clip limit at the same position (feedback round 25): past the new end, the clip is over.
      endMs = c.endMs ?? null;
      const over = endMs != null && startMs + c.positionMs >= endMs;
      audio.currentTime = (over ? endMs : startMs + c.positionMs) / 1000;
      clipEnded = over;
      if (c.play && !over) play(); else audio.pause();
      state();
      break;
    }
    case "play":
      play();
      break;
    case "pause":
      audio.pause();
      break;
    case "seek":
      clipEnded = false;
      audio.currentTime = (startMs + Math.max(0, c.ms)) / 1000;
      state();
      break;
    case "stop":
      audio.pause();
      audio.removeAttribute("src");
      audio.load();
      loaded = false;
      clipEnded = false;
      state();
      break;
    case "volume":
      audio.volume = c.v;
      break;
  }
}

window.addEventListener("message", (event) => {
  if (event.data !== "trackrecord-port" || !event.ports || !event.ports[0]) return;
  port = event.ports[0];
  port.onmessage = (m) => {
    let command;
    try { command = JSON.parse(m.data); } catch (e) { return; }
    run(command);
  };
  toApp({ t: "ready" });
});
