// Track Record guest page, internet transport (SPEC v4; DECISIONS.md D-180): the page reaches the
// host's phone over a direct WebRTC connection and sends it the same requests as on Wi-Fi, as
// messages. The link's fragment (#n.<room>.<secret>.<hostkey>, never sent to any server) names the
// room; the secret encrypts the connection offers on their way through the public relays, and only a
// host that proves it holds the named key is accepted (D-260).
import { joinNostr, joinTorrent, selfId } from "./vendor/trystero-0.26.0.mjs";
import {
  APP_ID, MusicPeerConnection, fromB64, handshake, importHostKey, keyId, localFingerprint, proofText, remoteFingerprint, verify,
} from "./p2p-common.js";

/** No host after this long on the first network: also look on the other one. */
const FALLBACK_MS = 10_000;
/** No host after this long: tell the guest. */
const SLOW_MS = 45_000;
const REQUEST_TIMEOUT_MS = 15_000;
/** How long a host may take to prove its key. */
const PROOF_TIMEOUT_MS = 10_000;
/** Images kept as object URLs; the oldest go once there are more (REVIEW-2 S-15). */
const MAX_IMAGES = 300;

/**
 * A transport like the page's HTTP one (see app.js): strings(), check(), post(), image(), connect(),
 * closed(). [onImage] is called when an image arrives, so the page can redraw.
 */
export function p2pTransport({ strategy, roomId, secret, hostKey, clientId, onImage, onStream }) {
  const networks = strategy === "t" ? [joinTorrent, joinNostr] : [joinNostr, joinTorrent];
  const rooms = [];
  /** The host's connection: {room, msg, peerId}, or null. */
  let host = null;
  /**
   * Every connection that proved the host's key, by peer id: after the host's page restarted (D-263) the
   * new one may show up before the old one is gone.
   */
  const proven = new Map();
  /** Peer id → the answer to our challenge, while waiting for it. */
  const proofs = new Map();
  let handlers = null;
  let joined = false;
  let nextId = 1;
  const pending = new Map();
  /**
   * An offer in the room couldn't be decrypted. That means a wrong secret only if the host never
   * shows up: anyone can post junk to a room's channel, so one such error mustn't break the page.
   */
  let passwordError = false;
  /** Image path → object URL, "" when there is none, null while loading. */
  const images = new Map();

  function join(joinRoom) {
    const room = joinRoom({ appId: APP_ID, password: secret, rtcPolyfill: MusicPeerConnection }, roomId, {
      onJoinError: (details) => {
        const error = String(details && details.error || "");
        if (/password|decrypt/i.test(error)) passwordError = true;
        if (/protocol/i.test(error) && handlers && handlers.onOutdated) handlers.onOutdated();
      },
      onPeerHandshake: handshake("guest"),
    });
    const msg = room.makeAction("msg");
    msg.onMessage = (data, { peerId }) => {
      if (host && host.room === room && host.peerId === peerId && typeof data === "string") receive(data);
    };
    const auth = room.makeAction("auth");
    auth.onMessage = (data, { peerId }) => {
      const answer = proofs.get(peerId);
      if (answer) answer(data);
    };
    room.onPeerJoin = async (peerId) => {
      if (!(await proves(room, auth, peerId))) return; // not the host whose key the link names
      proven.set(peerId, { room, msg, peerId });
      if (!host) useHost(proven.get(peerId));
    };
    // The host's audio stream: local files, played by the page (SPEC v4; D-181).
    room.onPeerStream = (s, peerId) => {
      if (host && host.room === room && host.peerId === peerId && onStream) onStream(s);
    };
    room.onPeerLeave = (peerId) => {
      proven.delete(peerId);
      if (!host || host.room !== room || host.peerId !== peerId) return;
      host = null;
      for (const [, p] of pending) { clearTimeout(p.timer); p.reject(new Error("host left")); }
      pending.clear();
      if (handlers) handlers.onLost();
      // The host's page was opened again meanwhile (D-263): go on with the new one.
      const next = proven.values().next().value;
      if (next) useHost(next);
    };
    rooms.push(room);
  }

  function useHost(h) {
    host = h;
    // One connection to the host is enough: leave the other network.
    for (const other of rooms) if (other !== h.room) other.leave();
    rooms.splice(0, rooms.length, h.room);
    openView();
  }

  /** Whether [peerId] holds the host key the link names, proven for this connection (D-260). */
  async function proves(room, auth, peerId) {
    try {
      const pc = room.getPeers()[peerId];
      if (!pc || !hostKey) return false;
      const nonce = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
      const answer = new Promise((resolve) => {
        proofs.set(peerId, resolve);
        setTimeout(() => resolve(null), PROOF_TIMEOUT_MS);
      });
      auth.send({ nonce }, { target: peerId });
      const reply = await answer;
      proofs.delete(peerId);
      if (!reply || typeof reply.key !== "string" || typeof reply.sig !== "string") return false;
      const spki = fromB64(reply.key);
      if ((await keyId(spki)) !== hostKey) return false;
      const key = await importHostKey(spki);
      return await verify(key, fromB64(reply.sig), proofText(nonce, localFingerprint(pc), remoteFingerprint(pc)));
    } catch (e) {
      proofs.delete(peerId);
      return false;
    }
  }

  function request(m, p, q, b) {
    return new Promise((resolve, reject) => {
      if (!host) {
        reject(new Error("not connected"));
        return;
      }
      const id = nextId++;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error("timeout")); }, REQUEST_TIMEOUT_MS);
      pending.set(id, { resolve, reject, timer });
      host.msg.send(JSON.stringify({ id, m, p, q: q || {}, b }), { target: host.peerId });
    });
  }

  function receive(text) {
    let message;
    try { message = JSON.parse(text); } catch (e) { return; }
    if (message.ev) {
      if (message.ev === "view" && handlers) {
        try { handlers.onView(JSON.parse(message.d)); } catch (e) { /* not a view */ }
      }
      return;
    }
    const p = pending.get(message.id);
    if (!p) return;
    pending.delete(message.id);
    clearTimeout(p.timer);
    p.resolve(message);
  }

  async function openView() {
    try {
      const reply = await request("GET", "/api/events", { c: clientId });
      if (reply.stream && handlers) handlers.onOpen();
    } catch (e) { /* the host left again; it reopens when it comes back */ }
  }

  const transport = {
    valid: !!(roomId && secret && hostKey),
    /** The texts before the host is reached come with the page (strings.json, made when publishing). */
    async strings() {
      const response = await fetch("strings.json");
      return response.json();
    },
    async check() { return "ok"; },
    /** The host's wall clock, for keeping music in step. */
    async time() {
      const reply = await request("GET", "/api/time");
      return JSON.parse(reply.text).now;
    },
    async post(path, body) {
      const reply = await request("POST", `/api/${path}`, {}, JSON.stringify(body));
      return JSON.parse(reply.text);
    },
    image(path) {
      if (images.has(path)) return images.get(path) || null;
      if (!host) return null;
      images.set(path, null);
      const [name, query] = path.split("?");
      request("GET", `/api/${name}`, Object.fromEntries(new URLSearchParams(query || "")))
        .then((reply) => {
          // Too many at once (the host's rate limit): asked again on a later redraw.
          if (reply.s === 429) { images.delete(path); setTimeout(onImage, 1000); return; }
          if (reply.s !== 200 || !reply.b64) { images.set(path, ""); return; }
          images.set(path, URL.createObjectURL(new Blob([fromB64(reply.b64)], { type: reply.ct })));
          // The oldest go, so a long game doesn't keep every image (REVIEW-2 S-15).
          while (images.size > MAX_IMAGES) {
            const [oldest, url] = images.entries().next().value;
            if (url) URL.revokeObjectURL(url);
            images.delete(oldest);
          }
          onImage();
        })
        .catch(() => images.delete(path));
      return null;
    },
    /** handlers: onView(view), onOpen(), onLost(), onInvalid(), onSlow(), onOutdated(). */
    connect(h) {
      handlers = h;
      if (host) { openView(); return; }
      if (joined) return;
      joined = true;
      join(networks[0]);
      setTimeout(() => { if (!host) join(networks[1]); }, FALLBACK_MS);
      setTimeout(() => {
        if (host || !handlers) return;
        if (passwordError) handlers.onInvalid();
        else if (handlers.onSlow) handlers.onSlow();
      }, SLOW_MS);
    },
    closed: () => !host,
    peerId: selfId,
  };
  return transport;
}
