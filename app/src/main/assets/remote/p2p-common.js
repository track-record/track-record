// Track Record internet play (SPEC v4; DECISIONS.md D-180): what the host's room and the guest page
// share. Bump PROTOCOL when the messages between page and host change.
export const APP_ID = "trackrecord";
/** Also RemoteProtocol.VERSION in the app (D-264); 2: the host proves its key (D-260). */
export const PROTOCOL = 6;

/** The largest message the host's room takes from a guest (Tunnel's limit, D-261). */
export const MAX_MESSAGE_BYTES = 128 * 1024;

/**
 * Trystero's handshake: each side says what it is. Guests only connect to the host, never to each
 * other (so they never learn each other's addresses), and both must speak the same protocol.
 */
export function handshake(role) {
  return async (_peerId, send, receive) => {
    await send({ role, protocol: PROTOCOL });
    const { data } = await receive();
    const other = data && data.role;
    if (role === "guest" && other !== "host") throw new Error("guests only connect to the host");
    if (role === "host" && other !== "guest") throw new Error("only guests connect to the host");
    if (!data || data.protocol !== PROTOCOL) throw new Error(`protocol ${data && data.protocol} instead of ${PROTOCOL}`);
  };
}

/** Streamed music (local files): Opus in stereo at up to this many bits per second (D-184). */
export const MUSIC_BITRATE = 192000;

/**
 * WebRTC's Opus defaults suit speech: mono and a low bitrate. Asks for stereo at MUSIC_BITRATE by
 * editing the Opus line of a session description; both sides use it, so each receives in stereo.
 */
export function musicSdp(sdp) {
  const opus = /a=rtpmap:(\d+) opus\/48000\/2/i.exec(sdp || "");
  if (!opus) return sdp;
  const pt = opus[1];
  return sdp.replace(new RegExp(`a=fmtp:${pt} ([^\r\n]*)`), (_line, params) => {
    const kept = params.split(";").map((p) => p.trim()).filter((p) => p && !/^(stereo|sprop-stereo|maxaveragebitrate)=/.test(p));
    return `a=fmtp:${pt} ${[...kept, "stereo=1", "sprop-stereo=1", `maxaveragebitrate=${MUSIC_BITRATE}`].join(";")}`;
  });
}

/**
 * The connection Trystero uses (its rtcPolyfill option): it calls setLocalDescription() without a
 * description, so this one makes the offer or answer itself and asks for music quality first.
 */
export class MusicPeerConnection extends RTCPeerConnection {
  async setLocalDescription(description) {
    if (description && description.type === "rollback") return super.setLocalDescription(description);
    let d = description;
    if (!d || !d.sdp) d = this.signalingState === "have-remote-offer" ? await this.createAnswer() : await this.createOffer();
    return super.setLocalDescription({ type: d.type, sdp: musicSdp(d.sdp) });
  }
}

// ---- The host's proof (D-260) ----
// Everyone with the link knows the room's secret, so the secret can't tell the host from a guest
// pretending to be one. The link also names the host's key (keyId); after connecting, a guest sends a
// random challenge and the host signs it together with both ends' DTLS fingerprints of this very
// connection, so a signature can't be passed on from another connection either.

const ECDSA = { name: "ECDSA", namedCurve: "P-256" };
const SIGNING = { name: "ECDSA", hash: "SHA-256" };

export const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
export const fromB64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
const b64url = (bytes) => b64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/** The key id in the link: the first 128 bits of the SHA-256 of the public key (SubjectPublicKeyInfo). */
export async function keyId(spki) {
  const digest = await crypto.subtle.digest("SHA-256", spki);
  return b64url(new Uint8Array(digest).slice(0, 16));
}

/** The DTLS fingerprint in a session description ("sha-256 AB:CD:…"), or "". */
export function fingerprint(description) {
  const m = /a=fingerprint:(\S+) (\S+)/i.exec((description && description.sdp) || "");
  return m ? `${m[1].toLowerCase()} ${m[2].toUpperCase()}` : "";
}

/** What the host signs: the guest's challenge and both ends of the connection. */
export function proofText(nonce, guestFingerprint, hostFingerprint) {
  return new TextEncoder().encode(`trackrecord-host|${nonce}|${guestFingerprint}|${hostFingerprint}`);
}

export const localFingerprint = (pc) => fingerprint(pc.currentLocalDescription || pc.localDescription);
export const remoteFingerprint = (pc) => fingerprint(pc.currentRemoteDescription || pc.remoteDescription);

export const importSigningKey = (pkcs8) => crypto.subtle.importKey("pkcs8", pkcs8, ECDSA, false, ["sign"]);
export const importHostKey = (spki) => crypto.subtle.importKey("spki", spki, ECDSA, false, ["verify"]);
export const sign = (key, data) => crypto.subtle.sign(SIGNING, key, data);
export const verify = (key, signature, data) => crypto.subtle.verify(SIGNING, key, signature, data);
