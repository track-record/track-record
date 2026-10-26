// Track Record: the host's internet room (SPEC v4; DECISIONS.md D-180). Runs in a WebView in the
// background of the app, which gives it WebRTC. The app sends "join" with the room and secret
// through a private message port; the room listens on both public networks (Nostr relays and
// BitTorrent trackers), and relays each guest's messages to the app and the app's answers back.
// It holds no game logic: the app's hub decides everything.
import { joinNostr, joinTorrent } from "../remote/vendor/trystero-0.26.0.mjs";
import {
  APP_ID, MAX_MESSAGE_BYTES, MUSIC_BITRATE, MusicPeerConnection, b64, fromB64, handshake, importSigningKey, localFingerprint,
  proofText, remoteFingerprint, sign,
} from "../remote/p2p-common.js";

let port = null;
const rooms = [];
/** Guest peer id → the room and message action it is reached through. */
const via = new Map();

function toApp(message) {
  if (port) port.postMessage(JSON.stringify(message));
}

window.addEventListener("message", (event) => {
  if (event.data !== "trackrecord-port" || !event.ports || !event.ports[0]) return;
  port = event.ports[0];
  port.onmessage = (m) => {
    let command;
    try { command = JSON.parse(m.data); } catch (e) { return; }
    if (command.t === "join") join(command.room, command.secret, command.key, command.pub);
    else if (command.t === "audio") onAudio(command);
    else if (command.t === "send") send(command.peer, command.data);
    else if (command.t === "leave") leave();
  };
  toApp({ t: "ready" });
});

function send(peerId, data) {
  const route = via.get(peerId);
  if (route) route.msg.send(data, { target: peerId });
}

function leave() {
  for (const room of rooms) room.leave();
  rooms.length = 0;
  for (const peerId of via.keys()) toApp({ t: "left", peer: peerId });
  via.clear();
}

// ---- Local files for internet guests (SPEC v4; D-181) ----
// The app sends what its player does ({src, playing, positionMs, atMs}; src null: nothing to stream).
// This page plays the same file, silently, at the same position, and sends it as one audio stream to
// every guest. Same device, so the host's clock needs no sync.

let audio = null;
let context = null;
let stream = null;
let follow = null;

function onAudio(command) {
  if (!command.src) {
    follow = null;
    if (audio) audio.pause();
    return;
  }
  ensureStream();
  if (audio.dataset.src !== command.src) {
    audio.dataset.src = command.src;
    audio.src = command.src;
  }
  follow = command;
  step();
}

function ensureStream() {
  if (audio) return;
  audio = new Audio();
  audio.preload = "auto";
  audio.id = "room-audio"; // in the page, so it can be inspected while debugging
  audio.hidden = true;
  document.body.appendChild(audio);
  context = new AudioContext();
  context.onstatechange = () => { audio.dataset.context = context.state; };
  audio.dataset.context = context.state;
  // The player's sound goes only into the stream, not to the speakers: the host hears its own player.
  // Playing takes Android's audio focus, so the app turns off the host player's focus handling
  // meanwhile (PlaybackFocus, D-181).
  const source = context.createMediaElementSource(audio);
  const destination = context.createMediaStreamDestination();
  source.connect(destination);
  stream = destination.stream;
  for (const [peerId, route] of via) route.room.addStream(stream, { target: peerId });
  setTimeout(raiseBitrate, 2000);
}

/** WebRTC starts music at a speech bitrate; ask for MUSIC_BITRATE (D-184). */
async function raiseBitrate() {
  for (const room of rooms) {
    for (const pc of Object.values(room.getPeers())) {
      for (const sender of pc.getSenders()) {
        if (!sender.track || sender.track.kind !== "audio") continue;
        const p = sender.getParameters();
        if (!p.encodings || !p.encodings.length) p.encodings = [{}];
        p.encodings[0].maxBitrate = MUSIC_BITRATE;
        try { await sender.setParameters(p); } catch (e) { /* keep the default */ }
      }
    }
  }
}

/** Applies each new signal from the host's player once: one seek, then play or pause (D-190). */
let appliedSignal = "";
function step() {
  if (!follow || !audio) return;
  if (context.state === "suspended") context.resume();
  const signal = `${follow.src}|${follow.playing}|${follow.positionMs}|${follow.atMs}`;
  if (signal === appliedSignal || audio.readyState < 1) return;
  appliedSignal = signal;
  const target = follow.playing ? follow.positionMs + (Date.now() - follow.atMs) : follow.positionMs;
  audio.currentTime = Math.max(0, target / 1000);
  if (follow.playing) audio.play().catch(() => {});
  else audio.pause();
}
setInterval(step, 250);

/** For checking the stream while debugging (devtools on the app's WebView): codec and bitrate per guest. */
window.trackRecordStreamStats = async () => {
  const out = [];
  for (const room of rooms) {
    for (const [peerId, pc] of Object.entries(room.getPeers())) {
      const stats = await pc.getStats();
      stats.forEach((s) => {
        if (s.type !== "outbound-rtp" || s.kind !== "audio") return;
        const codec = s.codecId && stats.get(s.codecId);
        out.push({ peerId, bytesSent: s.bytesSent, codec: codec && `${codec.mimeType} ${codec.channels}ch ${codec.sdpFmtpLine}` });
      });
    }
  }
  return out;
};

async function join(roomId, secret, pkcs8, pub) {
  leave();
  // The host's key, from the app (D-260): it signs each guest's challenge.
  const key = await importSigningKey(fromB64(pkcs8));
  for (const [name, joinRoom] of [["nostr", joinNostr], ["torrent", joinTorrent]]) {
    const room = joinRoom({ appId: APP_ID, password: secret, rtcPolyfill: MusicPeerConnection }, roomId, {
      onJoinError: (details) => toApp({ t: "log", text: `${name}: ${details && details.error}` }),
      onPeerHandshake: handshake("host"),
      // Nothing larger than a request is put back together from a guest's chunks (D-261).
      maxReceiveBytes: MAX_MESSAGE_BYTES,
    });
    const msg = room.makeAction("msg");
    msg.onMessage = (data, { peerId }) => {
      const route = via.get(peerId);
      if (route && route.room === room && typeof data === "string" && data.length <= MAX_MESSAGE_BYTES) toApp({ t: "msg", peer: peerId, data });
    };
    // A guest's challenge: signed with this connection's fingerprints, so it proves this very host (D-260).
    const auth = room.makeAction("auth");
    auth.onMessage = async (data, { peerId }) => {
      const pc = room.getPeers()[peerId];
      if (!pc || !data || typeof data.nonce !== "string" || data.nonce.length > 64) return;
      const signature = await sign(key, proofText(data.nonce, remoteFingerprint(pc), localFingerprint(pc)));
      auth.send({ key: pub, sig: b64(signature) }, { target: peerId });
    };
    room.onPeerJoin = (peerId) => {
      if (via.has(peerId)) return; // the same guest on the other network: the first connection wins
      via.set(peerId, { room, msg });
      toApp({ t: "joined", peer: peerId, via: name });
      if (stream) {
        room.addStream(stream, { target: peerId });
        setTimeout(raiseBitrate, 2000);
      }
    };
    room.onPeerLeave = (peerId) => {
      const route = via.get(peerId);
      if (!route || route.room !== room) return;
      via.delete(peerId);
      toApp({ t: "left", peer: peerId });
    };
    rooms.push(room);
  }
  toApp({ t: "log", text: `room open on nostr and torrent` });
}
