// Decodes songs for the desktop's AcoustID fingerprints (feedback round 55): Chromium's own decoders, resampled to the
// rate Chromaprint works at and mixed to mono, as Chromaprint's own resampler and downmix do. The first seconds go
// back to the app as 16-bit samples, base64-encoded, in parts.
// Command: decode {id, src, rate, seconds}. Answers: pcm {id, part, last, data} or failed {id}.
let port = null;

/** Characters of base64 per message: the message router carries large strings, but smaller ones go faster. */
const PART = 1 << 20;

function toApp(message) {
  if (port) port.postMessage(JSON.stringify(message));
}

function base64(bytes) {
  let text = "";
  for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(text);
}

async function decode(c) {
  const encoded = await (await fetch(c.src)).arrayBuffer();
  // An offline context at Chromaprint's rate: decodeAudioData resamples to it.
  const context = new OfflineAudioContext(1, 1, c.rate);
  const audio = await context.decodeAudioData(encoded);
  const length = Math.min(audio.length, c.rate * c.seconds);
  const channels = [];
  for (let ch = 0; ch < audio.numberOfChannels; ch++) channels.push(audio.getChannelData(ch));
  const samples = new Int16Array(length);
  for (let i = 0; i < length; i++) {
    let sum = 0;
    for (const data of channels) sum += data[i];
    const v = Math.round((sum / channels.length) * 32767);
    samples[i] = Math.max(-32768, Math.min(32767, v));
  }
  const text = base64(new Uint8Array(samples.buffer));
  for (let at = 0, part = 0; at < text.length || part === 0; at += PART, part++) {
    toApp({ t: "pcm", id: c.id, part, last: at + PART >= text.length, data: text.substring(at, at + PART) });
  }
}

window.addEventListener("message", (event) => {
  if (event.data !== "trackrecord-port" || !event.ports || !event.ports[0]) return;
  port = event.ports[0];
  port.onmessage = (m) => {
    let command;
    try { command = JSON.parse(m.data); } catch (e) { return; }
    if (command.t === "decode") decode(command).catch(() => toApp({ t: "failed", id: command.id }));
  };
  toApp({ t: "ready" });
});
