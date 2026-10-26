// Track Record guest page (SPEC v3): a remote control for one player. The host's phone is in charge;
// this page shows the view it pushes over an event stream and sends taps back as small requests.
import { httpTransport } from "./http.js";
import { music } from "./music.js";

// A module, so strict mode is on by itself.
(() => {
  const KEY = new URLSearchParams(location.search).get("k") || "";
  /**
   * An internet invite (SPEC v4): #n.<room>.<secret>.<hostkey> (n: Nostr first, t: BitTorrent trackers
   * first; the host's key id, D-260).
   */
  const INTERNET = /^#([nt])\.([A-Za-z0-9_-]{6,64})\.([A-Za-z0-9_-]{16,64})\.([A-Za-z0-9_-]{16,64})$/.exec(location.hash);
  /** How this page reaches the host (SPEC v4); set in start(). */
  let transport = null;
  const app = document.getElementById("app");
  const canvas = document.getElementById("scene");
  const toastEl = document.getElementById("toast");

  /** The fewest players a game keeps (GameEngine.MIN_PLAYERS): below that nobody can be removed. */
  const MIN_PLAYERS = 2;

  const S = {
    strings: null,
    view: null,
    es: null,
    connected: false,
    everConnected: false,
    invalid: false,
    overlay: null, // "menu" | "settings" | "scores" | "cards" | "theme" | "pick"
    yearEdit: false, // co-host: the year correction open on the reveal screen
    dismissed: new Set(), // bought or skipped songs closed here (feedback round 27)
    dragSlot: null, // the gap under the finger while dragging the guess (feedback round 27)
    adjusting: false, // co-host: the scoreboard's "Adjust vetoes" (or skips) on
    vetoMode: false,
    expanded: null,
    removing: null,
    seekingMs: null, // co-host: the slider's position while dragging it // co-host: the player the "Remove?" dialog asks about
    pendingVeto: null,
    guessesOpen: null, // Timeline reveal: the guesses section open (true), closed (false), or not decided yet
    audio: null,
    audioKey: "",
    audioAt: 0,
    digits: "",
    busy: false,
    stage: "",
  };

  // ---- Storage: the client id (localStorage, cookie fallback) and the theme picked here (cookie) ----

  function readCookie(name) {
    const m = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
    return m ? decodeURIComponent(m[1]) : null;
  }

  function writeCookie(name, value) {
    document.cookie = value == null
      ? `${name}=; max-age=0; path=/; SameSite=Lax`
      : `${name}=${encodeURIComponent(value)}; max-age=31536000; path=/; SameSite=Lax`;
  }

  const CID = (() => {
    let id = null;
    try { id = localStorage.getItem("hl_client"); } catch (e) { /* private mode */ }
    id = id || readCookie("hl_client");
    if (!id || !/^[A-Za-z0-9_-]{8,64}$/.test(id)) {
      const bytes = new Uint8Array(16);
      crypto.getRandomValues(bytes);
      id = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    }
    try { localStorage.setItem("hl_client", id); } catch (e) { /* private mode */ }
    writeCookie("hl_client", id);
    return id;
  })();

  const THEMES = ["LOW_POLY", "LIGHT", "DARK"];

  /** What this page runs on, so the host and the others can say phone, tablet or computer (DESIGN-REVIEW WD-01). */
  const DEVICE = (() => {
    const ua = navigator.userAgent;
    if (/iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return "TABLET";
    if (/Android/.test(ua) && !/Mobile/.test(ua)) return "TABLET";
    if ((navigator.userAgentData && navigator.userAgentData.mobile) || /Mobi|iPhone|iPod|Android/.test(ua)) return "PHONE";
    return "COMPUTER";
  })();
  const DEVICE_NAMES = { PHONE: "web_device_phone", TABLET: "web_device_tablet", COMPUTER: "web_device_computer" };

  /** "phone", "tablet" or "computer" for a kind the host sent (or this page's own). */
  function deviceName(kind) {
    return T(DEVICE_NAMES[kind] || "web_device_unknown");
  }

  /** The theme picked on this phone (DECISIONS.md D-107), else the host's. */
  function themeChoice() {
    const own = readCookie("hl_theme");
    if (own && THEMES.includes(own)) return own;
    return (S.view && S.view.theme && S.view.theme.choice) || "LOW_POLY";
  }

  // ---- Texts (all from the app's string resources, D-108) ----

  function T(key, ...args) {
    const s = (S.strings && S.strings[key]) || "";
    // Android placeholders: positional (%1$s, %2$d) or plain (%s, %d) in order.
    let next = 0;
    return s.replace(/%(?:(\d+)\$)?[sd]/g, (_, n) => String(args[n ? Number(n) - 1 : next++] ?? ""));
  }

  function plural(key, n, ...more) {
    return T(`${key}_${n === 1 ? "one" : "other"}`, n, ...more);
  }

  function esc(s) {
    return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  // ---- Theme ----

  let drawnTheme = null;

  /** The host's colours for the theme shown here (D-277), or null before the first view. */
  function palette() {
    const t = S.view && S.view.theme;
    return (t && t.palettes && t.palettes[themeChoice()]) || null;
  }

  function applyTheme() {
    const theme = themeChoice();
    const root = document.documentElement;
    if (root.dataset.theme !== theme) root.dataset.theme = theme;
    if (drawnTheme !== theme) {
      drawnTheme = theme;
      Scenes.draw(canvas, theme);
    }
    // The app's colours, sent with every view; app.css has the same values for the moments before.
    const p = palette();
    if (p) {
      for (const [name, value] of Object.entries(p.colors)) root.style.setProperty(name, value);
      root.style.setProperty("--heading-weight", String(p.headingWeight));
      root.style.setProperty("--base-dim-alpha", String(p.dim));
    }
    const t = S.view && S.view.theme;
    if (t) {
      root.style.setProperty("--radius", `${t.cornerRadius}px`);
      root.style.setProperty("--button-radius", t.buttonRadius >= 25 ? "999px" : `${t.buttonRadius}px`);
    }
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = getComputedStyle(root).getPropertyValue("--bg").trim().slice(0, 7) || "#000000";
  }

  let resizeTimer = 0;
  window.addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { drawnTheme = null; applyTheme(); }, 150);
  });

  /** Only for a card without an id, which the host never sends today. */
  function hash(text) {
    let h = 2166136261;
    for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
    return h >>> 0;
  }

  /** Compose's Color.hsv: [h] in degrees, [s] and [v] from 0 to 1. */
  function hsv(h, s, v) {
    const f = (n) => {
      const k = (n + h / 60) % 6;
      return Math.round((v - v * s * Math.max(0, Math.min(k, 4 - k, 1))) * 255);
    };
    return `rgb(${f(5)},${f(3)},${f(1)})`;
  }

  /**
   * A song card without a cover: the theme's card colours, picked from the song id exactly as the app
   * picks them (CardColors; D-099, DESIGN-REVIEW TH-03), so a card has the same colour everywhere.
   */
  function cardColors(c) {
    const p = palette();
    if (!p) return { fill: "var(--panel-high)", text: "var(--on)" };
    const id = c.songId != null ? c.songId : hash(`${c.year}|${c.title}|${c.artist || ""}`);
    if (p.cardFills && p.cardFills.length) {
      // Math.floorMod(id * 0x9E3779B1L, size) with Kotlin's 64-bit overflow.
      const n = BigInt(p.cardFills.length);
      const product = BigInt.asIntN(64, BigInt(id) * 0x9E3779B1n);
      return { fill: p.cardFills[Number(((product % n) + n) % n)], text: p.cardText };
    }
    const hue = (((id * 137.508) % 360) + 360) % 360;
    return { fill: hsv(hue, p.cardSaturation, p.cardValue), text: p.cardText };
  }

  // ---- Building blocks ----

  const ICONS = {
    play: "M8 5v14l11-7z",
    pause: "M6 19h4V5H6v14zm8-14v14h4V5h-4z",
    replay: "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z",
    volume: "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    scores: "M7.5 21H2V9h5.5v12zm7.25-18h-5.5v18h5.5V3zM22 11h-5.5v10H22V11z",
    settings: "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
    edit: "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 0 0 0-1.41l-2.34-2.34a.996.996 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
    close: "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    undo: "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
    expandLess: "M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z",
    expandMore: "M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z",
    info: "M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
    check: "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    palette: "M12 3a9 9 0 0 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z",
  };

  function svgIcon(name) {
    return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${ICONS[name]}"/></svg>`;
  }

  function icon(name, act, label) {
    return `<button class="icon-button" data-act="${act}" aria-label="${esc(label)}" title="${esc(label)}">${svgIcon(name)}</button>`;
  }

  /** Closest Year: this phone's player may guess now; with hidden guesses everyone who hasn't yet (feedback round 36). */
  function guessTurn(g, me) {
    if (!me || g.phase !== "GUESSING") return false;
    return g.hiddenGuesses ? (g.waitingFor || []).includes(me) : g.currentGuesser === me;
  }

  /** Rank and Distance count points, not cards (feedback round 36). */
  function collectsCards(g) {
    return g.mode === "TIMELINE" || g.scoring === "CLOSEST";
  }

  /**
   * A player's circle: colour plus initials (and photo), never colour alone. On a fill of the player's own
   * colour, [ring] outlines it (DESIGN-REVIEW UX-02).
   */
  function avatar(p, size, ring) {
    const style = `width:${size}px;height:${size}px;background:${esc(p.color)};color:${esc(p.onColor)};border-color:${esc(ring || p.color)};font-size:${Math.round(size * 0.38)}px`;
    const cls = ring ? "avatar ringed" : "avatar";
    if (p.photo) {
      // With its key a photo can be kept by the browser; a new photo has a new key (D-270).
      const key = p.photoKey ? `&v=${encodeURIComponent(p.photoKey)}` : "";
      const src = transport && transport.image(`photo?p=${encodeURIComponent(p.id)}${key}`);
      // The photo identifies the player: coloured ring, no initials (feedback round 11).
      if (src) return `<span class="${cls}" style="${style}" aria-hidden="true"><img src="${esc(src)}" alt=""></span>`;
    }
    return `<span class="${cls}" style="${style}" aria-hidden="true">${esc(p.initials)}</span>`;
  }

  /**
   * The song's progress for everyone; play/pause and replay only on this phone's turn (feedback rounds
   * 8 and 11). The bar moves on by itself between the host's updates.
   */
  function audioRow(g, canControl, seekable) {
    const a = g.audio || {};
    // Always shown, as they explain the bar; usable only on this phone's turn (feedback round 13).
    const off = a.loaded && canControl ? "" : "disabled";
    const svg = svgIcon;
    const buttons = `<button class="round-button" data-act="togglePlay" aria-label="${esc(T(a.playing ? "web_pause" : "web_play"))}" ${off}>${svg(a.playing ? "pause" : "play")}</button>
         <button class="round-button tonal" data-act="replay" aria-label="${esc(T("web_replay"))}" ${off}>${svg("replay")}</button>`;
    // Internet guests: the music's volume behind a speaker symbol, so it is easy to find (D-192).
    const speaker = INTERNET
      ? `<button class="icon-button volume-button" data-act="volumeToggle" aria-label="${esc(T("web_volume"))}" aria-expanded="${!!S.volumeOpen}">${svg("volume")}</button>`
      : "";
    const slider = INTERNET && S.volumeOpen ? volumeSlider() : "";
    // The app's note under the bar, only when the song can't be played (feedback round 43).
    const note = a.error ? `<span class="play-note error">${esc(T("web_playback_error"))}</span>` : "";
    // A co-host's reveal: the host's slider with the time, to jump in the song (feedback round 44).
    const bar = seekable && a.durationMs > 0
      ? `<div class="progress-box"><input type="range" class="seek" min="0" max="${a.durationMs}" step="100" value="${Math.round(progressPercent() * a.durationMs / 100)}" style="--seek:${progressPercent()}%" data-seek="1" aria-label="${esc(T("web_seek"))}"><span class="play-time">${esc(playTime())}</span>${note}</div>`
      : `<div class="progress-box"><div class="progress"><div class="progress-fill" style="width:${progressPercent()}%"></div><div class="progress-track"></div></div>${note}</div>`;
    return `<div class="play-row">${buttons}${bar}${speaker}</div>${slider}`;
  }

  function progressPercent() {
    const a = S.audio;
    if (!a || !a.durationMs) return 0;
    const position = a.playing ? a.positionMs + (performance.now() - S.audioAt) : a.positionMs;
    return Math.max(0, Math.min(100, (position / a.durationMs) * 100));
  }

  /** "0:12 / 0:30", as under the host's slider; while dragging, where the finger is. */
  function playTime() {
    const a = S.audio;
    if (!a || !a.durationMs) return "";
    const at = S.seekingMs != null ? S.seekingMs : (progressPercent() / 100) * a.durationMs;
    const fmt = (ms) => { const t = Math.floor(ms / 1000); return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, "0")}`; };
    return T("web_play_time", fmt(at), fmt(a.durationMs));
  }

  function animateProgress() {
    const fills = document.querySelectorAll(".progress-fill");
    if (fills.length) {
      const width = `${progressPercent()}%`;
      fills.forEach((f) => { f.style.width = width; });
    }
    const seek = document.querySelector("input.seek");
    if (seek && S.seekingMs == null && S.audio) {
      seek.value = String(Math.round(progressPercent() * S.audio.durationMs / 100));
      seek.style.setProperty("--seek", `${progressPercent()}%`);
    }
    const time = document.querySelector(".play-time");
    if (time) time.textContent = playTime();
    requestAnimationFrame(animateProgress);
  }
  requestAnimationFrame(animateProgress);

  /** The music volume slider (menu, and under the progress bar after tapping the speaker; D-185, D-192). */
  function volumeSlider() {
    return `<label class="volume-row"><span>${esc(T("web_volume"))}</span><input type="range" min="0" max="100" step="1" value="${Math.round(Music.volume() * 100)}" data-volume="1" aria-label="${esc(T("web_volume"))}"></label>`;
  }

  /** "Tap a gap twice to confirm": this phone's choice (cookie), else the host's default (feedback round 11). */
  function confirmTwice() {
    const own = readCookie("hl_confirm");
    if (own === "1" || own === "0") return own === "1";
    return !!(S.view && S.view.confirmTwice);
  }

  /**
   * This player's vetoes (Timeline) or skips (Closest Year) on the left, with skip and buy beside them
   * when possible (feedback rounds 11, 25; D-193). The count has a fixed width and the buttons fixed
   * labels, so nothing moves when the numbers change.
   */
  function extraActions(g, players, me) {
    const mine = players[me];
    if (!mine || !["AWAITING_DRAW", "PLACING", "VETOING", "GUESSING"].includes(g.phase)) return "";
    const buttons = [];
    let count = "";
    if (g.mode === "TIMELINE") {
      count = plural("web_vetoes", mine.vetoes);
      if (g.phase === "PLACING" && g.activeId === me && g.skipCost != null) {
        buttons.push(`<button class="btn outlined small" data-act="skip" ${mine.vetoes >= g.skipCost && !S.busy ? "" : "disabled"}>${esc(T("web_skip", plural("web_vetoes", g.skipCost)))}</button>`);
      }
      if (g.buyCost != null && (g.phase === "PLACING" || g.phase === "AWAITING_DRAW")) {
        buttons.push(`<button class="btn outlined small" data-act="buy" ${mine.vetoes >= g.buyCost && !S.busy ? "" : "disabled"}>${esc(T("web_buy", plural("web_vetoes", g.buyCost)))}</button>`);
      }
    } else if (g.closestSkips || g.buyCost != null) {
      count = plural("web_skips", mine.skips);
      if (g.phase === "GUESSING" && g.currentGuesser === me && !g.guesses.length) {
        buttons.push(`<button class="btn outlined small" data-act="skip" ${mine.skips > 0 && !S.busy ? "" : "disabled"}>${esc(T("web_skip_plain"))}</button>`);
      }
      // Buying a card for skips, before the reveal (feedback round 27).
      if (g.buyCost != null && (g.phase === "GUESSING" || g.phase === "AWAITING_DRAW")) {
        buttons.push(`<button class="btn outlined small" data-act="buy" ${mine.skips >= g.buyCost && !S.busy ? "" : "disabled"}>${esc(T("web_buy", plural("web_skips", g.buyCost)))}</button>`);
      }
    }
    if (!count) return "";
    // "You have 2 vetoes", the count in bold (feedback round 27).
    const [before, after] = T("web_you_have", "\u0001").split("\u0001");
    return `<div class="wallet"><span class="wallet-count">${esc(before || "")}<strong>${esc(count)}</strong>${esc(after || "")}</span><span class="wallet-buttons">${buttons.join("")}</span></div>`;
  }

  /**
   * A bought card or a skipped song, shown to everyone until closed (feedback rounds 11, 27): a dialog with
   * the song's card, as on the host (DESIGN-REVIEW LY-03). One at a time.
   */
  function songNotice(g, players) {
    for (const [field, key] of [["purchase", "web_bought"], ["skipped", "web_skipped"]]) {
      const event = g[field];
      const p = event && players[event.playerId];
      if (!p) continue;
      const id = `${field}|${g.round}|${event.playerId}|${event.card.songId}|${event.card.year}`;
      if (S.dismissed.has(id)) continue;
      return dialog(T(key, p.name), card(event.card),
        `<button class="text-button" data-act="dismiss" data-notice="${esc(id)}">${esc(T("web_ok"))}</button>`);
    }
    return "";
  }

  /** A dialog like the app's: title, content, buttons on the right (LY-03, IN-03). */
  function dialog(title, content, buttons) {
    return `<div class="dialog-scrim"><div class="dialog" role="dialog" aria-modal="true"><h2 class="headline-small">${esc(title)}</h2>${content}<div class="actions">${buttons}</div></div></div>`;
  }

  function chip(p, size, extra) {
    return `<span class="chip">${avatar(p, size)}<strong>${esc(p.name)}</strong>${extra ? `<span class="muted">${esc(extra)}</span>` : ""}</span>`;
  }

  /** Album covers on cards and at reveal: this phone's choice (cookie), else the host's (feedback round 12). */
  function coversOn() {
    const own = readCookie("hl_covers");
    if (own === "1" || own === "0") return own === "1";
    return !(S.view && S.view.showCovers === false);
  }

  /** The cover's address, or null while it is still on its way (the page re-renders when it arrives). */
  function coverUrl(songId) {
    return transport && transport.image(`cover?s=${encodeURIComponent(songId)}`);
  }

  /** A timeline card: the cover behind a dark gradient (like the app), else the theme's card colour. */
  function card(c) {
    const colors = cardColors(c);
    const coverSrc = coversOn() && c.songId != null ? coverUrl(c.songId) : null;
    const cover = coverSrc ? `<img class="card-cover" src="${esc(coverSrc)}" alt="">` : "";
    return `<div class="card${cover ? " has-cover" : ""}" style="background:${colors.fill};color:${colors.text}">${cover}
      <span class="year">${c.year}</span>
      <span class="what"><div class="title">${esc(c.title)}</div><div class="artist">${esc(c.artist || T("web_unknown_artist"))}</div></span>
    </div>`;
  }

  function hero(title, text, extra = "") {
    return `<div class="hero"><h1>${esc(title)}</h1>${text ? `<p>${esc(text)}</p>` : ""}${extra}</div>`;
  }

  function bottom(content) {
    return content ? `<div class="bottom"><div class="bottom-inner">${content}</div></div>` : "";
  }

  // ---- Screens ----

  function render() {
    // Don't rebuild the page under a finger on the volume slider; draw once it is let go.
    if (S.holdRender) {
      S.renderPending = true;
      return;
    }
    applyTheme();
    if (!S.strings) {
      app.innerHTML = "";
      return;
    }
    document.title = T("app_name");
    const v = S.view;
    let html;
    if (S.invalid || (transport && !transport.valid)) {
      // An internet guest came with a link, not the QR code (WD-01).
      html = hero(T("app_name"), T(INTERNET ? "web_invalid_link_internet" : "web_invalid_link"));
    } else if (v && v.blocked) {
      html = hero(T("app_name"), T("web_blocked", deviceName(DEVICE)));
    } else if (S.outdated) {
      html = hero(T("app_name"), T("web_outdated"));
    } else if (!v) {
      html = hero(T("app_name"), T(S.everConnected ? "web_reconnecting" : S.slow ? "web_p2p_unreachable" : "web_connecting"));
    } else if (!v.game && v.lobby && (!v.me || S.overlay === "pick")) {
      // The host is setting up a game: pick a player already (feedback round 32).
      html = pickScreen(v);
    } else if (!v.game && v.lobby) {
      const me = v.lobby.find((p) => p.id === v.me);
      html = topbar("", false) + hero(T("web_lobby_title", me ? me.name : ""), T("web_lobby_text"), me ? avatar(me, 72) : "") +
        bottom(`<button class="btn outlined" data-act="switch">${esc(T("web_switch_player"))}</button>`);
    } else if (!v.game) {
      html = topbar("", false) + hero(T("app_name"), T("web_waiting_title")) + `<p class="center muted">${esc(T("web_waiting_text"))}</p>`;
    } else if (v.paused) {
      html = topbar("", false) + hero(T("web_paused_title"), T("web_paused_text"));
    } else if (v.left && !v.me && S.overlay !== "pick") {
      // Removed from the game: this phone watches (feedback round 36).
      html = gameScreen(v);
    } else if (!v.me || S.overlay === "pick") {
      html = pickScreen(v);
    } else {
      html = gameScreen(v);
    }
    if (S.everConnected && !S.connected && !S.invalid) html = `<div class="banner">${esc(T("web_reconnecting"))}</div>` + html;
    if (v && !v.blocked && !S.invalid) html = soundBanner() + html;
    html += overlay(v) + dialogs(v);
    // An open sheet keeps its place across updates (REVIEW.md B-04; D-271), and so does the part above
    // the keypad.
    const sheet = app.querySelector(".sheet");
    const sheetScroll = sheet && S.sheetOf === S.overlay ? sheet.scrollTop : 0;
    const pinned = app.querySelector(".pinned-scroll");
    const pinnedScroll = pinned ? pinned.scrollTop : 0;
    app.innerHTML = html;
    const next = app.querySelector(".sheet");
    if (next && sheetScroll) next.scrollTop = sheetScroll;
    S.sheetOf = next ? S.overlay : null;
    const nextPinned = app.querySelector(".pinned-scroll");
    // Guessing: the keypad fixed at the bottom, the rest scrolling above it (TH-02).
    document.body.classList.toggle("pinned", !!nextPinned);
    if (nextPinned && pinnedScroll) nextPinned.scrollTop = pinnedScroll;
    // Room below the content for the bar fixed at the bottom, whatever it holds (keypad, the co-host's
    // buttons; feedback rounds 18, 26).
    const bar = app.querySelector(".bottom");
    app.style.paddingBottom = bar ? `${bar.offsetHeight + 16}px` : "";
    // Timeline reveal: close the guesses section when the scoreboard then no longer fits on screen. Decided
    // once per reveal, right after the first layout and before it is painted.
    if (S.guessesOpen === null && document.querySelector(".guesses-panel")) {
      const board = document.querySelector(".scoreboard-panel");
      S.guessesOpen = !(board && board.getBoundingClientRect().bottom + window.scrollY > window.innerHeight - (bar ? bar.offsetHeight : 0));
      if (!S.guessesOpen) render();
    }
  }

  function topbar(title, inGame) {
    const v = S.view;
    let right = icon("palette", "theme", T("web_theme"));
    if (inGame) {
      const me = v.game.players.find((p) => p.id === v.me);
      const menu = me ? `<button class="icon-button" data-act="menu" aria-label="${esc(T("web_menu"))}">${avatar(me, 36)}</button>` : "";
      // The game's rules, settings and playlists (feedback round 36); at game over only the menu (LY-06).
      right = v.game.phase === "GAME_OVER" ? menu
        : icon("info", "info", T("web_info")) + icon("scores", "scores", T("web_scoreboard")) + icon("settings", "settings", T("web_settings")) + menu;
    }
    return `<div class="topbar"><h2>${esc(title)}</h2>${right}</div>`;
  }

  function pickScreen(v) {
    // A running game's players, or those of the game being set up (feedback round 32).
    const players = v.game ? v.game.players : v.lobby || [];
    const devices = v.devices || {};
    let html = topbar(T("web_pick_title"), false) + `<p class="muted">${esc(T("web_pick_hint", deviceName(DEVICE)))}</p>`;
    for (const p of players) {
      const mine = p.id === v.me;
      const taken = v.taken.includes(p.id);
      const tag = mine ? T("web_you") : v.pendingPlayer === p.id ? T("web_takeover_waiting") : taken ? T("web_taken", deviceName(devices[p.id])) : "";
      html += `<button class="player-row" data-act="claim" data-player="${esc(p.id)}">${avatar(p, 44)}<span class="name">${esc(p.name)}</span><span class="tag">${esc(tag)}</span></button>`;
    }
    if (v.me) html += bottom(`<button class="btn outlined" data-act="close">${esc(T("web_cancel"))}</button>`);
    return html;
  }

  function gameScreen(v) {
    const g = v.game;
    const players = Object.fromEntries(g.players.map((p) => [p.id, p]));
    // Game over: no title, as the app's end screen has none above its own (LY-06).
    const title = g.phase === "GAME_OVER" ? "" : g.round ? T("web_round", g.round) : T("app_name");
    let content;
    let keypad = "";
    if (g.phase === "GAME_OVER") content = gameOver(g, players, v.me);
    // Waiting for the next song: a spinner and the text, as in the app (LY-07).
    else if (g.phase === "AWAITING_DRAW" || g.round == null) content = `<div class="waiting"><div class="spinner" role="progressbar"></div><p class="muted">${esc(T("web_next_song"))}</p></div>`;
    else if (g.mode === "TIMELINE") content = timeline(g, players, v.me);
    else {
      const cy = closestYear(g, players, v.me);
      content = cy.main;
      keypad = cy.keypad || "";
    }
    const playing = g.round != null && !["AWAITING_DRAW", "GAME_OVER", "REVEALED"].includes(g.phase);
    const myTurn = (g.phase === "PLACING" && g.activeId === v.me) || guessTurn(g, v.me);
    // The player who placed the card and those who could still veto may play and replay while the
    // vetoes are decided; a co-host always, like the host (feedback rounds 23, 24; D-186, D-188, D-189).
    const canPlay = myTurn || v.coHost || (g.phase === "VETOING" && (g.activeId === v.me || g.vetoEligible.includes(v.me)));
    const top = (playing ? audioRow(g, canPlay) : "") + extraActions(g, players, v.me);
    const left = v.left && !v.me
      ? `<div class="panel notice"><p>${esc(T("web_left"))}</p><button class="btn outlined small" data-act="switch">${esc(T("web_switch_player"))}</button></div>` : "";
    const body = left + top + content;
    // Guessing: the year entry stays at the bottom, the rest scrolls above it (TH-02).
    return topbar(title, true) + (keypad ? `<div class="pinned-scroll">${body}</div>${keypad}` : body);
  }

  /**
   * A co-host's phone has the host's buttons where the host has them (D-189, feedback round 26): Reveal
   * at the bottom, counting down like the host's, and Undo guess in Closest Year. The host's phone
   * checks each tap.
   */
  function hostBar(g) {
    if (!S.view.coHost) return "";
    const undo = (key, enabled) => `<button class="btn outlined host-undo" data-act="hostUndo" ${enabled && !S.busy ? "" : "disabled"}>${svgIcon("undo")}<span>${esc(T(key))}</span></button>`;
    if (g.phase === "VETOING") {
      const w = g.vetoWindow || {};
      // No Undo veto; Reveal appears once the co-host's own player decided, like on the host with one
      // player on it (feedback round 27).
      const me = S.view.me;
      if (me !== g.activeId && g.vetoEligible.includes(me) && !(w.declined || []).includes(me)) return "";
      const picking = (w.picking || []).length > 0;
      const label = picking ? T("web_host_reveal_waiting")
        : w.lockedSeconds > 0 ? T("web_host_reveal_locked", w.lockedSeconds)
          : w.autoRevealSeconds > 0 ? T("web_host_reveal_auto", w.autoRevealSeconds) : T("web_host_reveal");
      return `<div class="host-row">` +
        `<button class="btn main" data-act="hostReveal" ${picking || w.lockedSeconds > 0 || S.busy ? "disabled" : ""}>${esc(label)}</button></div>`;
    }
    if (g.phase === "GUESSING") {
      return `<div class="host-row">${undo("web_host_undo_guess", g.guesses.length > 0)}` +
        `<button class="btn main" data-act="hostReveal" ${g.currentGuesser == null && !S.busy ? "" : "disabled"}>${esc(T("web_host_reveal"))}</button></div>`;
    }
    return "";
  }

  /** The co-host's year correction (D-189), with the year's sources the page knows (IN-04). */
  function yearEditor(g) {
    if (!S.view.coHost || !S.yearEdit) return "";
    return `<div class="panel"><h3 class="heading">${esc(T("web_host_year"))}</h3>` +
      `<p class="body-medium">${esc(T("web_round_counted", g.revealed.year))}</p>${sourcesLine(g.revealed)}` +
      `<input type="number" inputmode="numeric" id="host-year" class="year-input" value="${g.revealed.year}" data-hold="1" aria-label="${esc(T("web_host_year"))}">` +
      `<div class="row"><button class="btn outlined" data-act="yearEdit">${esc(T("web_cancel"))}</button>` +
      `<button class="btn" data-act="hostYear" ${S.busy ? "disabled" : ""}>${esc(T("web_host_save"))}</button></div></div>`;
  }

  /**
   * Who named song and artist, as on the host's result screen: a co-host marks it (D-189), everyone else
   * sees the marks (IF-02).
   */
  function namingPanel(g, players) {
    const n = g.naming;
    if (!n) return "";
    const co = S.view.coHost;
    const who = g.mode === "CLOSEST_YEAR" || n.candidates.length > 1 ? `<p class="title-small">${esc(T("web_host_named_who"))}</p>` : "";
    const marks = n.candidates.map((id) => {
      const on = n.namedBy === id;
      const label = esc(T("web_host_named", players[id] ? players[id].name : id));
      return co
        ? `<button class="named-toggle${on ? " on" : ""}" data-act="hostNamed" data-player="${esc(id)}" aria-pressed="${on}" ${S.busy ? "disabled" : ""}>${label}</button>`
        : `<div class="named-toggle${on ? " on" : ""}">${label}</div>`;
    }).join("");
    const hint = { ACTIVE: "web_host_named_hint_active", VETOERS: "web_host_named_hint_vetoers", ALL: "web_host_named_hint_all" }[n.callers] || "web_host_named_hint_skip";
    return `<div class="panel naming-panel">${who}${marks}<p class="body-small">${esc(T(hint))}</p></div>`;
  }

  /** "Tag 1995 · MusicBrainz 1993" when they differ, or "Year corrected", as under the host's year (IF-01). */
  function sourcesLine(r) {
    if (r.corrected) return `<p class="body-small">${esc(T("web_corrected"))}</p>`;
    if (r.tagYear != null && r.musicBrainzYear != null && r.tagYear !== r.musicBrainzYear) {
      return `<p class="body-medium">${esc(T("web_sources", r.tagYear, r.musicBrainzYear))}</p>`;
    }
    return "";
  }

  /** The clip length (D-191) in the co-host's settings, like the host's: off, 10 to 120 s in steps of 5 (feedback round 34). */
  function clipStepper(g) {
    const now = g.clipLimit;
    const down = now == null ? 0 : now <= 10 ? 0 : now - 5;
    const up = now == null ? 10 : Math.min(120, now + 5);
    const label = now == null ? T("web_clip_off") : plural("web_seconds", now);
    return `<div class="clip-row"><span>${esc(T("web_clip_length"))}</span>` +
      `<button data-act="hostClip" data-seconds="${down}" ${now == null ? "disabled" : ""} aria-label="−">−</button>` +
      `<span class="clip-value">${esc(label)}</span>` +
      `<button data-act="hostClip" data-seconds="${up}" ${now === 120 ? "disabled" : ""} aria-label="+">+</button></div>`;
  }

  /** The start offset in the co-host's settings, like the host's: 0 to 60 s in steps of 5, from the next song (feedback round 34). */
  function offsetStepper(g) {
    const now = g.startOffset || 0;
    return `<div class="clip-row"><span>${esc(T("web_start_offset"))}<br><span class="muted">${esc(T("web_start_offset_hint"))}</span></span>` +
      `<button data-act="hostOffset" data-seconds="${Math.max(0, now - 5)}" ${now <= 0 ? "disabled" : ""} aria-label="−">−</button>` +
      `<span class="clip-value">${esc(plural("web_seconds", now))}</span>` +
      `<button data-act="hostOffset" data-seconds="${Math.min(60, now + 5)}" ${now >= 60 ? "disabled" : ""} aria-label="+">+</button></div>`;
  }

  function countdown(g) {
    const w = g.vetoWindow;
    if (!w) return "";
    if (w.lockedSeconds > 0) return `<p class="countdown">${esc(T("web_reveal_in", w.lockedSeconds))}</p>`;
    if (w.autoRevealSeconds > 0) return `<p class="countdown">${esc(T("web_auto_reveal_in", w.autoRevealSeconds))}</p>`;
    return "";
  }

  function timeline(g, players, me) {
    const active = players[g.activeId];
    const myTurn = g.activeId === me;
    if (g.phase === "REVEALED") return revealed(g, players);
    if (g.phase === "PLACING") {
      if (myTurn) {
        return `<p>${esc(T(confirmTwice() ? "web_your_turn_twice" : "web_your_turn"))}</p>` + gaps(g, players, "select") +
          bottom(`<button class="btn main" data-act="lockIn" ${g.selectedSlot == null || S.busy ? "disabled" : ""}>${esc(T("web_lock_in"))}</button>`);
      }
      return `<p class="chip">${avatar(active, 32)}<span>${esc(T("web_placing", active.name))}</span></p>` + gaps(g, players, null);
    }
    // Vetoing
    const eligible = g.vetoEligible.includes(me);
    const declined = g.vetoWindow && g.vetoWindow.declined.includes(me);
    // The host knows we're picking a gap (it waits for us); follow it, e.g. after a reload.
    if (g.vetoWindow && (g.vetoWindow.picking || []).includes(me)) S.vetoMode = true;
    const vetoed = g.vetoes.some((x) => x.playerId === me);
    if (!eligible || declined) S.vetoMode = false;
    let status;
    let actions = "";
    let mode = null;
    if (myTurn) {
      status = T("web_waiting_vetoes");
    } else if (eligible && !declined) {
      if (S.vetoMode) {
        status = T(confirmTwice() ? "web_veto_pick_twice" : "web_veto_pick");
        mode = "veto";
        actions = `<div class="row"><button class="btn outlined main" data-act="cancelVeto">${esc(T("web_cancel"))}</button>` +
          `<button class="btn main" data-act="confirmVeto" ${S.pendingVeto == null || S.busy ? "disabled" : ""}>${esc(T("web_confirm"))}</button></div>`;
      } else {
        status = T("web_veto_question", active.name);
        actions = `<div class="row"><button class="btn" data-act="startVeto">${esc(T("web_veto"))}</button>` +
          `<button class="btn outlined" data-act="decline" ${S.busy ? "disabled" : ""}>${esc(T("web_no_veto"))}</button></div>`;
      }
    } else if (vetoed) {
      status = T("web_veto_placed");
    } else if (declined) {
      status = T("web_declined");
    } else {
      status = T("web_waiting_reveal");
    }
    return `<p>${esc(status)}</p>` + (S.view.coHost ? "" : countdown(g)) + gaps(g, players, mode) + bottom(actions + hostBar(g));
  }

  /** The active player's timeline with its gaps; [mode] makes free gaps tappable ("select" or "veto"). */
  function gaps(g, players, mode) {
    const cards = g.cards[g.activeId] || [];
    const active = players[g.activeId];
    const chosenSlot = g.phase === "PLACING" ? (mode === "select" && S.dragSlot != null ? S.dragSlot : g.selectedSlot) : g.lockedSlot;
    let html = `<div class="timeline">`;
    for (let slot = 0; slot <= cards.length; slot++) {
      if (g.gaps.includes(slot)) {
        const veto = g.vetoes.find((x) => x.slot === slot);
        if (chosenSlot === slot) {
          const again = mode === "select" && confirmTwice();
          const inner = `${avatar(active, 32, active.onColor)}<span class="label">${esc(T(again ? "web_tap_again_place" : "web_gap_here"))}</span>`;
          html += mode === "select"
            ? `<button class="gap chosen" data-act="select" data-slot="${slot}" style="background:${esc(active.color)};color:${esc(active.onColor)};border-color:${esc(active.color)}">${inner}</button>`
            : `<div class="gap chosen" style="background:${esc(active.color)};color:${esc(active.onColor)};border-color:${esc(active.color)}">${inner}</div>`;
        } else if (veto) {
          const p = players[veto.playerId];
          html += `<div class="gap vetoed" style="border-color:${esc(p.color)}">${avatar(p, 32)}<span class="label">${esc(T("web_gap_veto"))}</span></div>`;
        } else if (mode === "veto" && S.pendingVeto === slot && !S.busy) {
          // The marked gap: Confirm places the veto; with double-tap on, so does a second tap (feedback round 14).
          const me = players[S.view.me];
          html += `<button class="gap pending" data-act="veto" data-slot="${slot}" style="border-color:${esc(me.color)}">${avatar(me, 32)}<span class="label">${esc(T(confirmTwice() ? "web_tap_again" : "web_veto_here"))}</span></button>`;
        } else if (mode && !S.busy) {
          html += `<button class="gap" data-act="${mode}" data-slot="${slot}" aria-label="${esc(T("web_gap_choose"))}">+</button>`;
        } else {
          html += `<div class="gap"></div>`;
        }
      }
      if (slot < cards.length) html += card(cards[slot]);
    }
    return html + `</div>`;
  }

  function revealed(g, players) {
    const r = g.revealed;
    if (!r) return "";
    const who = r.outcomePlayer ? players[r.outcomePlayer] : null;
    // A tie that both score: every winner (feedback round 36).
    const many = (r.outcomePlayers || []).map((id) => players[id]).filter(Boolean);
    const shared = r.outcome === "won" && many.length > 1;
    const key = shared ? "web_outcome_won_shared" : { kept: "web_outcome_kept", stolen: "web_outcome_stolen", discarded: "web_outcome_discarded", won: "web_outcome_won", none: "web_outcome_none" }[r.outcome];
    const outcomeName = shared ? many.map((p) => p.name).join(T("list_separator")) : who ? who.name : "";
    const outcomeAvatars = shared ? many.map((p) => avatar(p, 32)).join("") : who ? avatar(who, 32) : "";
    const coverSrc = coversOn() && r.songId != null ? coverUrl(r.songId) : null;
    const cover = coverSrc ? `<img class="reveal-cover" src="${esc(coverSrc)}" alt="">` : "";
    const border = { WON: "won", LOST: "lost", OTHER: "other" }[S.view.tone] || "";
    const year = `<div class="display-large">${r.year}</div>`;
    // The app's card: cover, year, sources, song, album, outcome (feedback round 16; IF-01).
    const lost = r.outcome === "kept" && g.vetoes.length ? `<p class="body-medium">${esc(T("web_vetoes_lost"))}</p>` : "";
    const cardHtml = `<div class="panel reveal${border ? ` outcome-${border}` : ""}">${cover}
      ${S.view.coHost ? `<div class="year-line"><span></span>${year}<span>${icon("edit", "yearEdit", T("web_host_year_details"))}</span></div>` : year}
      ${sourcesLine(r)}
      <div class="title-large">${esc(r.title)}</div>
      <div>${esc(r.artist || T("web_unknown_artist"))}</div>
      ${r.album ? `<div class="body-small">${esc(r.album)}</div>` : ""}
      <div class="outcome">${outcomeAvatars}<span>${esc(T(key, outcomeName))}</span></div>${lost}
    </div>`;
    // Streamed songs: the song's page on Deezer under playback, as on the host (only Deezer's own https links; IF-03).
    const deezer = typeof r.link === "string" && r.link.startsWith("https://www.deezer.com/")
      ? `<a class="text-button" href="${esc(r.link)}" target="_blank" rel="noopener noreferrer">${esc(T("web_open_deezer"))}</a>` : "";
    // Playback only for a co-host, who controls it and may play the whole song like the host (feedback round 43).
    const a = g.audio || {};
    const whole = S.view.coHost && a.whole
      ? `<button class="text-button" data-act="hostWhole" ${S.busy ? "disabled" : ""}>${esc(T(a.preview ? "web_full_preview" : "web_full_song"))}</button>` : "";
    const links = whole || deezer ? `<div class="links">${whole}${deezer}</div>` : "";
    const playback = (S.view.coHost ? audioRow(g, true, true) : "") + links;
    const guesses = g.mode === "CLOSEST_YEAR" ? yearAxis(g, players) + guessList(g, players) : timelineGuesses(g, players);
    const next = S.view.coHost ? bottom(`<button class="btn main" data-act="hostNext" ${S.busy ? "disabled" : ""}>${esc(T("web_host_next"))}</button>`) : "";
    return cardHtml + yearEditor(g) + namingPanel(g, players) + playback + guesses + scoreboard(g, players) + next;
  }

  /**
   * Timeline reveal: the active player's timeline with the guess, the vetoes and, in green, where the
   * song fits; gaps nobody chose are left out. Starts closed when it would push the scoreboard out of
   * view (feedback round 21).
   */
  function timelineGuesses(g, players) {
    if (g.mode !== "TIMELINE" || !g.revealed) return "";
    const open = S.guessesOpen !== false;
    const head = `<button class="section-toggle" data-act="toggleGuesses" aria-expanded="${open}"><h3 class="heading">${esc(T("web_guesses"))}</h3><span class="chevron">${svgIcon(open ? "expandLess" : "expandMore")}</span></button>`;
    if (!open) return `<div class="panel guesses-panel">${head}</div>`;
    const cards = g.cards[g.activeId] || [];
    const year = g.revealed.year;
    let rows = "";
    for (let slot = 0; slot <= cards.length; slot++) {
      if (g.gaps.includes(slot)) {
        const guess = g.lockedSlot === slot;
        const vetoes = g.vetoes.filter((v) => v.slot === slot);
        const fits = (slot === 0 || cards[slot - 1].year <= year) && (slot === cards.length || year <= cards[slot].year);
        if (guess || vetoes.length || fits) {
          const chips = [];
          if (guess) chips.push(`<span class="gchip">${avatar(players[g.activeId], 28)}${esc(T("web_result_guess"))}</span>`);
          for (const v of vetoes) chips.push(`<span class="gchip">${avatar(players[v.playerId], 28)}${esc(T("web_gap_veto"))}</span>`);
          if (fits) chips.push(`<span class="gchip fits">${svgIcon("check")}${esc(T("web_fits_here", year))}</span>`);
          rows += `<div class="result-gap${fits ? " fits" : ""}">${chips.join("")}</div>`;
        }
      }
      if (slot < cards.length) rows += card(cards[slot]);
    }
    return `<div class="panel guesses-panel">${head}<div class="timeline">${rows}</div></div>`;
  }

  /** The host's scoreboard card: the scores after this round, highest first (feedback round 12). */
  function scoreboard(g, players) {
    if (!g.scoreboard || !g.scoreboard.length) return "";
    const rows = g.scoreboard.map((st, i) => {
      const p = players[st.playerId] || (g.removed || []).find((x) => x.id === st.playerId);
      if (!p) return "";
      // The host's wording, vetoes (or skips) included (feedback round 17); removed players greyed, unranked (round 36).
      const score = finalDetail(g, { score: st.score, vetoes: st.vetoes, skips: st.skips });
      const gained = st.gained > 0 ? `<span class="gained">${esc(T("web_gained", st.gained))}</span>` : "";
      const detail = st.removed ? `${T("web_removed")} · ${score}` : score;
      return `<div class="board-row${st.removed ? " removed" : ""}"><span class="rank">${st.removed ? "–" : `${i + 1}.`}</span>${avatar(p, 32)}<span class="name">${esc(p.name)}</span>${gained}<span class="detail">${esc(detail)}</span></div>`;
    }).join("");
    return `<div class="panel scoreboard-panel"><h3 class="heading">${esc(T("web_scoreboard"))}</h3>${rows}</div>`;
  }

  /** Closest Year: the guesses on an axis from the lowest guessable year to now, like the host's. */
  /**
   * The guesses on a line from the lowest guessable year to now. With [picker] (this phone's turn), a tap
   * or drag on it picks a year: it fills the digits and shows faded until Guess (feedback round 35).
   */
  function yearAxis(g, players, picker) {
    const span = Math.max(1, g.maxYear - g.minYear);
    const at = (year) => `${Math.min(1, Math.max(0, (year - g.minYear) / span)) * 100}%`;
    // Hidden guesses: the others' years aren't sent (feedback round 36).
    // The same year again: a little higher, so both show.
    const shown = g.guesses.filter((x) => x.year != null && players[x.playerId]);
    let marks = shown.map((x, i) => {
      const stacked = shown.slice(0, i).filter((y) => y.year === x.year).length;
      return `<span class="axis-mark" style="left:${at(x.year)};margin-top:${-16 * stacked}px">${avatar(players[x.playerId], 36)}</span>`;
    }).join("");
    if (g.revealed) marks = `<span class="axis-year" style="left:${at(g.revealed.year)}"></span>` + marks;
    const pending = picker && S.digits.length === 4 ? Number(S.digits) : null;
    if (pending != null && pending >= g.minYear && pending <= g.maxYear) {
      marks += `<span class="axis-mark pending" style="left:${at(pending)}">${avatar(picker, 36)}</span>`;
    }
    return `<div class="axis${picker ? " pickable" : ""}" data-min="${g.minYear}" data-max="${g.maxYear}"><span class="axis-line"></span>${marks}</div>
      <div class="axis-labels"><span>${g.minYear}</span><span>${g.maxYear}</span></div>`;
  }

  function guessList(g, players) {
    if (g.mode !== "CLOSEST_YEAR" || !g.guesses.length) return "";
    return `<div class="panel"><h3 class="heading">${esc(T("web_guesses"))}</h3>` + g.guesses.map((x) => {
      const p = players[x.playerId];
      if (!p) return "";
      // After the reveal: how far off each guess was, in its own column so the years line up (feedback round 15).
      const off = g.revealed ? `<span class="off">${esc(plural("web_years_off", Math.abs(x.year - g.revealed.year)))}</span>` : "";
      // Rank and Distance: the points it earns (feedback round 36).
      const points = g.revealed && g.roundPoints && g.roundPoints[x.playerId] != null ? `<span class="points">${esc(T("web_gained", g.roundPoints[x.playerId]))}</span>` : "";
      const year = x.year == null ? `<span class="muted">${esc(T("web_guessed"))}</span>` : `<span class="year">${x.year}</span>`;
      // The app's sizes: 28 while guessing, 32 after the reveal.
      return `<div class="guess-row">${avatar(p, g.revealed ? 32 : 28)}<span class="name">${esc(p.name)}</span>${year}${off}${points}</div>`;
    }).join("") + `</div>`;
  }

  /**
   * Returns the part that scrolls ([main]) and, on this phone's turn, the year entry fixed below it
   * ([keypad]): on the screen's background, as in the app, with a co-host's buttons in a bar under it.
   */
  function closestYear(g, players, me) {
    if (g.phase === "REVEALED") return { main: revealed(g, players) };
    const guesser = players[g.currentGuesser];
    if (guessTurn(g, me)) {
      const slots = [0, 1, 2, 3].map((i) => `<span class="slot${i === S.digits.length ? " next" : ""}">${esc(S.digits[i] || "")}</span>`).join("");
      const taken = g.distinctGuesses && g.guesses.length ? `<p class="body-small center">${esc(T("web_guess_taken", g.guesses.map((x) => x.year).join(T("list_separator"))))}</p>` : "";
      const keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "back", "0", "ok"].map((k) => {
        if (k === "back") return `<button data-act="digit" data-key="back" aria-label="${esc(T("web_backspace"))}" ${S.digits ? "" : "disabled"}>⌫</button>`;
        if (k === "ok") return `<button class="ok" data-act="guess" ${S.digits.length === 4 && !S.busy ? "" : "disabled"}>${esc(T("web_guess"))}</button>`;
        return `<button data-act="digit" data-key="${k}">${k}</button>`;
      }).join("");
      const host = hostBar(g);
      return {
        main: `<p>${esc(T("web_your_guess"))}</p>` + yearAxis(g, players, players[me]) + guessList(g, players),
        keypad: `<div class="keypad-panel">
          <div class="digits" aria-label="${esc(S.digits)}">${slots}</div>
          <p class="body-small center">${esc(`${T("web_guess_range", g.minYear, g.maxYear)} · ${T("web_axis_hint")}`)}</p>${taken}
          <div class="keypad">${keys}</div>${host ? `<div class="bar">${host}</div>` : ""}
        </div>`,
      };
    }
    if (g.hiddenGuesses && (g.waitingFor || []).length) {
      // Everyone guesses at once: who is still guessing (feedback round 36).
      const waiting = g.waitingFor.map((id) => players[id]).filter(Boolean);
      return { main: `<p class="chip">${waiting.map((p) => avatar(p, 28)).join("")}<span>${esc(T("web_waiting_for", waiting.map((p) => p.name).join(T("list_separator"))))}</span></p>` +
        yearAxis(g, players) + guessList(g, players) + bottom(hostBar(g)) };
    }
    const status = guesser ? T("web_guessing", guesser.name) : T("web_waiting_reveal");
    return { main: `<p class="chip">${guesser ? avatar(guesser, 32) : ""}<span>${esc(status)}</span></p>` + yearAxis(g, players) + guessList(g, players) + bottom(hostBar(g)) };
  }

  /** The end screen, laid out like the host's (feedback round 16; LY-06). */
  function gameOver(g, players, me) {
    const winners = g.winners.map((id) => players[id]).filter(Boolean);
    const line = winners.length === 1 ? T("web_game_over_winner", winners[0].name) : T("web_game_over_shared", winners.map((p) => p.name).join(T("list_separator")));
    const pool = g.endReason === "POOL_EMPTY" ? `<p class="body-medium center">${esc(T("web_game_over_pool_empty"))}</p>` : "";
    // Removed players last, greyed out (feedback round 36).
    const ranked = [...g.players].sort((a, b) => b.score - a.score).concat(g.removed || []);
    const removed = new Set((g.removed || []).map((p) => p.id));
    const cards = collectsCards(g);
    const rows = ranked.map((p) => {
      const open = S.expanded === p.id && cards;
      const list = open ? `<div class="timeline">${(g.cards[p.id] || []).map(card).join("")}</div>` : "";
      const you = p.id === me ? ` · ${T("web_you")}` : "";
      const detail = removed.has(p.id) ? `${T("web_removed")} · ${finalDetail(g, p)}` : finalDetail(g, p);
      // Rank and Distance have no cards to show.
      const tap = cards ? ` data-act="expand" data-player="${esc(p.id)}" role="button" tabindex="0" aria-expanded="${open}"` : "";
      return `<div class="final-row${cards ? " tappable" : ""}${removed.has(p.id) ? " removed" : ""}"${tap}>${avatar(p, 44)}<span class="who"><span class="title-medium">${esc(p.name + you)}</span><span class="detail">${esc(detail)}</span></span></div>${list}`;
    }).join("");
    return `<div class="final">
      <h2 class="center">${esc(T("web_game_over"))}</h2>
      <div class="winners">${winners.map((p) => avatar(p, 72)).join("")}</div>
      <h1 class="headline-medium center">${esc(line)}</h1>${pool}
      <h3 class="heading final-title">${esc(T("web_final_scores"))}</h3>
      <div class="final-list">${rows}</div>
    </div>`;
  }

  /** Cards or points (feedback round 36), "3 of 10 cards" with a [target]. */
  function mainScore(g, n, target) {
    // Cards in Song Order and in Closest scoring, as the rules call them (WD-03).
    const [of, one] = collectsCards(g) ? ["web_cards_of", "web_cards"] : ["web_points_of", "web_points"];
    return target != null ? T(of, n, target) : plural(one, n);
  }

  /** The host's end-screen wording: "3 cards · 1 veto", "2 wins · 1 skip", "12 points · 1 skip". */
  function finalDetail(g, p, target) {
    const main = mainScore(g, p.score, target);
    if (g.mode === "TIMELINE") return `${main} · ${plural("web_vetoes", p.vetoes)}`;
    return g.closestSkips ? `${main} · ${plural("web_skips", p.skips)}` : main;
  }

  function scoreDetail(g, p) {
    return finalDetail(g, p, g.target);
  }

  /**
   * The scoreboard: every player with the target; a tap shows their cards (feedback round 36). Removed
   * players come last, greyed out. Two lines a player with lines between, as in the app (LY-02); during
   * the reveal, the scores after Next, as on the scoreboard card (UX-01).
   */
  function scoreRows(g, me) {
    const removed = new Set((g.removed || []).map((p) => p.id));
    const after = g.phase === "REVEALED" ? Object.fromEntries((g.scoreboard || []).map((st) => [st.playerId, st])) : {};
    return `<div class="score-list">` + g.players.concat(g.removed || []).map((p) => {
      const gone = removed.has(p.id);
      const a = after[p.id];
      const shown = a ? { ...p, score: a.score, vetoes: a.vetoes, skips: a.skips } : p;
      const detail = gone ? `${T("web_removed")} · ${scoreDetail(g, shown)}` : scoreDetail(g, shown);
      const you = p.id === me ? ` · ${T("web_you")}` : "";
      // A co-host corrects vetoes (Timeline) or skips (Closest Year) like on the host (D-189).
      const count = g.mode === "TIMELINE" ? p.vetoes : p.skips;
      const adjust = S.view && S.view.coHost && S.adjusting && !gone
        ? `<span class="adjust"><button data-act="hostAdjust" data-player="${esc(p.id)}" data-delta="-1" aria-label="${esc(T("web_host_less", p.name))}" ${count > 0 ? "" : "disabled"}>−</button><button data-act="hostAdjust" data-player="${esc(p.id)}" data-delta="1" aria-label="${esc(T("web_host_more", p.name))}">+</button></span>`
        : "";
      const cards = collectsCards(g);
      // A co-host removes players like the host: tap the row, then Remove from game (feedback round 43).
      const removable = S.view && S.view.coHost && !gone && g.phase !== "GAME_OVER" && g.players.length > MIN_PLAYERS;
      const tappable = cards || removable;
      const open = tappable && S.expanded === p.id;
      const own = withRevealed(g, p.id, g.cards[p.id] || []);
      const cardList = cards ? (own.length ? `<div class="timeline">${own.map(card).join("")}</div>` : `<p class="muted">${esc(T("web_no_cards"))}</p>`) : "";
      const remove = removable ? `<button class="text-button danger" data-act="removeAsk" data-player="${esc(p.id)}">${esc(T("web_remove"))}</button>` : "";
      const list = open ? `<div class="expanded">${cardList}${remove}</div>` : "";
      const tap = tappable ? ` data-act="expand" data-player="${esc(p.id)}" role="button" tabindex="0" aria-expanded="${open}"` : "";
      return `<div class="score-row${gone ? " removed" : ""}${tappable ? " tappable" : ""}"${tap}>${avatar(p, 44)}<span class="who"><span class="name">${esc(p.name + you)}</span><span class="detail">${esc(detail)}</span></span>${adjust}</div>${list}`;
    }).join("") + `</div>`;
  }

  /** During the reveal, [cards] with the song just won, as the scores after Next count it (UX-01). */
  function withRevealed(g, playerId, cards) {
    const r = g.phase === "REVEALED" && g.revealed;
    if (!r) return cards;
    const won = g.mode === "TIMELINE" ? ["kept", "stolen"].includes(r.outcome) && r.outcomePlayer === playerId
      : g.scoring === "CLOSEST" && (r.outcomePlayers || []).includes(playerId);
    if (!won) return cards;
    const c = { year: r.year, title: r.title, artist: r.artist, songId: r.songId };
    // Song Order keeps cards in year order; Closest Year collects them in order.
    if (g.mode !== "TIMELINE") return cards.concat([c]);
    const at = cards.findIndex((x) => x.year > c.year);
    return at < 0 ? cards.concat([c]) : [...cards.slice(0, at), c, ...cards.slice(at)];
  }

  /** The info sheet (feedback round 36): the rules folded away, the settings and the playlists, worded by the host. */
  function infoSheet(g) {
    const info = g.info;
    if (!info) return `<h2>${esc(T("web_info"))}</h2>`;
    const open = !!S.rulesOpen;
    // Cards like the host's sheet: each section on its own card, paragraphs apart (feedback round 37).
    const rules = `<div class="panel info-panel info-rules"><button class="section-toggle" data-act="toggleRules" aria-expanded="${open}"><h3 class="heading">${esc(T("web_info_rules"))}</h3><span class="chevron">${svgIcon(open ? "expandLess" : "expandMore")}</span></button>` +
      (open ? info.rules.map((t) => `<p class="body-medium">${esc(t)}</p>`).join("") : "") + `</div>`;
    const settings = `<div class="panel info-panel"><h3 class="heading">${esc(T("web_info_settings"))}</h3>` +
      info.settings.map((l) => `<div class="info-row"><span>${esc(l.label)}</span><span>${esc(l.value)}</span></div>`).join("") + `</div>`;
    const list = (items) => items.map((n) => `<div class="body-medium">${esc(n)}</div>`).join("");
    const songs = `<div class="panel info-panel"><h3 class="heading">${esc(T("web_info_songs"))}</h3>` +
      `<p class="info-sub">${esc(T("web_info_included"))}</p>${list(info.included)}` +
      `<p class="info-sub">${esc(T("web_info_excluded"))}</p>${list(info.excluded)}</div>`;
    return `<h2>${esc(T("web_info"))}</h2>` + rules + settings + songs;
  }

  // ---- Overlays ----

  function overlay(v) {
    if (!S.overlay || S.overlay === "pick") return "";
    let body = "";
    if (S.overlay === "menu" && v && v.game) {
      // Titled with the player it belongs to (UX-06); no cards to show in Rank and Distance (feedback round 36).
      const me = v.game.players.find((p) => p.id === v.me);
      body = (me ? `<div class="chip">${avatar(me, 36)}<h2>${esc(me.name)}</h2></div>` : "") + (collectsCards(v.game) ? `<button class="option" data-act="cards">${esc(T(v.game.mode === "TIMELINE" ? "web_my_cards" : "web_my_songs"))}</button>` : "") +
        `<button class="option" data-act="switch">${esc(T("web_switch_player"))}</button>` +
        `<button class="option" data-act="theme">${esc(T("web_theme"))}</button>` +
        // Purely cosmetic, so with the player's own things (feedback round 26).
        toggleRow("toggleCovers", T("web_show_covers"), coversOn());
    } else if (S.overlay === "settings" && v && v.game) {
      // Beside the scoreboard (feedback round 26); a co-host also has the host's clip length (D-191).
      body = `<h2>${esc(T("web_settings"))}</h2>` +
        (v.coHost && v.game.phase !== "GAME_OVER" ? clipStepper(v.game) + offsetStepper(v.game) : "") +
        // Internet guests play the music themselves: their own volume (D-185).
        (INTERNET ? volumeSlider() : "") +
        toggleRow("toggleConfirm", T("web_confirm_twice"), confirmTwice(), T("web_confirm_twice_hint"));
    } else if (S.overlay === "scores" && v && v.game) {
      const g = v.game;
      // A co-host corrects vetoes or skips behind the host's switch (feedback round 26).
      const adjustable = v.coHost && (g.mode === "TIMELINE" || g.closestSkips);
      const timelineMode = g.mode === "TIMELINE";
      const adjust = adjustable
        ? toggleRow("adjustToggle", T(timelineMode ? "web_host_adjust_vetoes" : "web_host_adjust_skips"), S.adjusting) +
          `<p class="muted">${esc(T(timelineMode ? "web_host_adjust_vetoes_hint" : "web_host_adjust_skips_hint"))}</p>`
        : "";
      body = `<h2>${esc(T("web_scoreboard"))}</h2>` + adjust + scoreRows(g, v.me);
    } else if (S.overlay === "info" && v && v.game) {
      body = infoSheet(v.game);
    } else if (S.overlay === "cards" && v && v.game) {
      const mine = v.game.cards[v.me] || [];
      body = `<h2>${esc(T(v.game.mode === "TIMELINE" ? "web_my_cards" : "web_my_songs"))}</h2>` +
        (mine.length ? `<div class="timeline">${mine.map(card).join("")}</div>` : `<p class="muted">${esc(T("web_no_cards"))}</p>`);
    } else if (S.overlay === "theme") {
      const own = readCookie("hl_theme");
      const host = (v && v.theme && v.theme.choice) || "LOW_POLY";
      body = `<h2>${esc(T("web_theme"))}</h2>` +
        `<button class="option${own ? "" : " selected"}" data-act="setTheme" data-theme=""><span class="dot"></span>${esc(T("web_theme_host", T(`theme_${host}`)))}</button>` +
        THEMES.map((t) => `<button class="option${own === t ? " selected" : ""}" data-act="setTheme" data-theme="${t}"><span class="dot"></span>${esc(T(`theme_${t}`))}</button>`).join("");
    } else {
      return "";
    }
    return `<div class="overlay" data-act="close"><div class="sheet" data-stop="1"><div class="sheet-handle" data-handle="1"><span></span></div>${body}<button class="btn tonal close" data-act="close">${esc(T("web_close"))}</button></div></div>`;
  }

  /** A label (and [hint]) with a switch on the right, like the app's settings (feedback round 12). */
  function toggleRow(act, label, on, hint) {
    const text = hint ? `<span class="labels"><span>${esc(label)}</span><span class="hint">${esc(hint)}</span></span>` : `<span>${esc(label)}</span>`;
    return `<button class="toggle-row" data-act="${act}" role="switch" aria-checked="${on}">${text}<span class="switch${on ? " on" : ""}"></span></button>`;
  }

  /** The dialogs over everything: taking over a player (IN-03), a bought card or skipped song (LY-03). */
  function dialogs(v) {
    if (S.takeover) {
      const t = S.takeover;
      return dialog(T("web_takeover_title", t.name), `<p class="text">${esc(T("web_takeover_confirm", t.name, deviceName(t.device)))}</p>`,
        `<button class="text-button" data-act="takeoverNo">${esc(T("web_cancel"))}</button><button class="text-button" data-act="takeoverYes">${esc(T("web_takeover_take"))}</button>`);
    }
    const g = v && v.game;
    if (g && S.removing && v.coHost) {
      const p = g.players.find((x) => x.id === S.removing);
      if (p) {
        // Their own Song Order turn ends with them, as the host's dialog says.
        const turn = g.mode === "TIMELINE" && g.activeId === p.id && g.phase !== "AWAITING_DRAW";
        const text = T("web_remove_text", p.name) + (turn ? " " + T("web_remove_turn") : "");
        return dialog(T("web_remove_title", p.name), `<p class="text">${esc(text)}</p>`,
          `<button class="text-button" data-act="removeNo">${esc(T("web_cancel"))}</button><button class="text-button" data-act="removeYes">${esc(T("web_remove_confirm"))}</button>`);
      }
      S.removing = null;
    }
    if (!g || v.paused || !(v.me || v.left)) return "";
    return songNotice(g, Object.fromEntries(g.players.map((p) => [p.id, p])));
  }

  // ---- Music for internet guests (music.js; SPEC v4; D-181) ----
  // Browsers only play sound after a tap, so a banner asks for one.
  const Music = music({ listenNow: () => S.view && S.view.listen, soundOn: () => !!S.soundOn, transport: () => transport });

  /**
   * Browsers let a page play sound only after a tap on it. Any first tap does (picking a player, say),
   * so the banner only shows for a phone that came back to the game without one (feedback round 27).
   */
  function enableSound() {
    if (S.soundOn) return;
    S.soundOn = true;
    Music.unlock();
    Music.syncClock();
    Music.follow();
  }
  if (INTERNET) {
    for (const type of ["click", "keydown"]) {
      document.addEventListener(type, () => { if (!S.soundOn) { enableSound(); setTimeout(render, 0); } }, true);
    }
    // Some browsers allow sound without a tap (e.g. a site played often): then no banner at all.
    try {
      const probe = new AudioContext();
      if (probe.state === "running") S.soundOn = true;
      probe.close();
    } catch (e) { /* no Web Audio: the banner stays */ }
  }

  function soundBanner() {
    if (!INTERNET || S.soundOn || !(S.view && S.view.me)) return "";
    return `<button class="sound-banner" data-act="sound">${esc(T("web_sound_on"))}</button>`;
  }

  // ---- Talking to the host (SPEC v4) ----
  // Everything above talks to the host only through a transport: HTTP to the phone's server on the
  // same Wi-Fi (http.js), or, over the internet, a direct connection (p2p.js).

  const post = (path, body) => transport.post(path, body);

  async function act(type, extra = {}) {
    if (S.busy || !S.view || !S.view.game) return;
    S.busy = true;
    render();
    try {
      if (type === "lockIn") {
        // Right after a drag: the last gap reaches the host first, and Lock in names the gap shown, so
        // it doesn't depend on the view catching up (D-259).
        await dragSent();
        const shown = S.dragSlot != null ? S.dragSlot : S.view.game.selectedSlot;
        if (shown != null) extra = { slot: shown, ...extra };
      }
      const reply = await post("action", { client: CID, version: S.view.game.version, round: S.view.game.round, type, ...extra });
      if (!reply.ok) toast(reply.error);
      else if (type === "veto") S.vetoMode = false;
      else if (type === "guess") S.digits = "";
    } catch (e) {
      toast(T("web_error_network"));
    } finally {
      S.busy = false;
      render();
    }
  }

  async function claim(id, takeover = false) {
    try {
      const reply = await post("claim", { client: CID, player: id, takeover, device: DEVICE });
      if (!reply.ok && reply.taken) {
        // Played on another device right now: ask first, in the page's own dialog (IN-03).
        const p = (S.view.game ? S.view.game.players : S.view.lobby || []).find((x) => x.id === id);
        S.takeover = { id, name: p ? p.name : id, device: (S.view.devices || {})[id] };
      } else if (reply.pending) toast(T("web_takeover_waiting"));
      else if (!reply.ok) toast(reply.error);
      else S.overlay = null;
    } catch (e) {
      toast(T("web_error_network"));
    }
    render();
  }

  let toastTimer = 0;
  function toast(text) {
    if (!text) return;
    toastEl.textContent = text;
    toastEl.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove("show"), 3000);
  }

  /** Resets per-stage input and buzzes when it becomes this phone's turn to act. */
  function onView() {
    const v = S.view;
    // The host answered a takeover request (D-182): denied is said once, allowed closes the picker.
    if (v && v.deniedPlayer && S.deniedShown !== v.deniedPlayer) { S.deniedShown = v.deniedPlayer; toast(T("web_takeover_denied")); }
    if (v && !v.deniedPlayer) S.deniedShown = null;
    if (v && v.me && S.overlay === "pick" && S.wasPending) S.overlay = null;
    S.wasPending = !!(v && v.pendingPlayer);
    const g = v && v.game;
    if (!g || v.paused) {
      if (S.overlay !== "theme") S.overlay = null;
      return;
    }
    if (S.dragSlot != null && !drag && !live.sending && live.slot == null && g.selectedSlot === S.dragSlot) S.dragSlot = null;
    const audioKey = JSON.stringify(g.audio || {});
    if (audioKey !== S.audioKey) {
      S.audioKey = audioKey;
      S.audio = g.audio;
      S.audioAt = performance.now();
    }
    const myMove = v.me && (
      (g.phase === "PLACING" && g.activeId === v.me) ||
      guessTurn(g, v.me) ||
      (g.phase === "VETOING" && g.vetoEligible.includes(v.me)));
    // Hidden guesses: others guessing meanwhile don't clear the digits being typed (feedback round 36).
    const stage = `${g.round}|${g.phase}|${g.hiddenGuesses ? "" : g.currentGuesser}|${myMove}`;
    if (stage !== S.stage) {
      if (myMove && S.stage && navigator.vibrate) navigator.vibrate(150);
      S.stage = stage;
      S.vetoMode = false;
      S.pendingVeto = null;
      S.digits = "";
      S.guessesOpen = null;
      S.yearEdit = false;
      S.dragSlot = null;
    }
  }

  function connect() {
    transport.connect({
      onView(view) {
        S.view = view;
        S.connected = true;
        S.everConnected = true;
        onView();
        render();
      },
      onOpen() { S.connected = true; render(); },
      onLost() { S.connected = false; render(); },
      onInvalid() { S.invalid = true; render(); },
      onSlow() { S.slow = true; render(); },
      onOutdated() { S.outdated = true; render(); },
    });
  }

  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && transport && !S.invalid && transport.closed()) connect();
  });

  // A photo or cover that can't be loaded disappears; a card falls back to its colour. One listener
  // instead of inline onerror handlers, which the Content-Security-Policy blocks (D-168).
  document.addEventListener("error", (e) => {
    const img = e.target;
    if (!(img instanceof HTMLImageElement)) return;
    if (img.parentNode && img.parentNode.classList) img.parentNode.classList.remove("has-cover");
    img.remove();
  }, true);

  // The volume slider: live while dragging, without redrawing the page under the finger.
  document.addEventListener("pointerdown", (e) => { if (e.target.closest && e.target.closest("[data-volume], [data-seek]")) S.holdRender = true; });
  document.addEventListener("focusin", (e) => { if (e.target.dataset && e.target.dataset.hold) S.holdRender = true; });
  document.addEventListener("focusout", (e) => { if (e.target.dataset && e.target.dataset.hold) releaseVolume(); });
  const releaseVolume = () => {
    if (!S.holdRender) return;
    S.holdRender = false;
    if (S.renderPending) { S.renderPending = false; render(); }
  };
  document.addEventListener("pointerup", releaseVolume);
  document.addEventListener("pointercancel", releaseVolume);
  document.addEventListener("input", (e) => {
    if (e.target.dataset && e.target.dataset.volume) Music.setVolume(Number(e.target.value) / 100);
    // A co-host's slider: the time follows the finger; the host jumps when it is let go.
    if (e.target.dataset && e.target.dataset.seek) {
      S.seekingMs = Number(e.target.value);
      e.target.style.setProperty("--seek", `${(S.seekingMs / Number(e.target.max)) * 100}%`);
    }
  });
  document.addEventListener("change", (e) => {
    if (!(e.target.dataset && e.target.dataset.seek)) return;
    const positionMs = Number(e.target.value);
    S.seekingMs = null;
    act("hostSeek", { positionMs });
  });

  // ---- Taps ----

  // ---- Closest Year: pick the guess on the line (feedback round 35) ----
  let axisPointer = null;
  function pickOnAxis(clientX) {
    const axis = document.querySelector(".axis.pickable");
    if (!axis) return;
    const r = axis.getBoundingClientRect();
    const min = Number(axis.dataset.min);
    const max = Number(axis.dataset.max);
    const f = Math.min(1, Math.max(0, (clientX - r.left) / r.width));
    const year = String(Math.round(min + f * (max - min)));
    if (year !== S.digits) { S.digits = year; render(); }
  }
  /**
   * While dragging on the year line or a gap, no text gets selected: with a mouse the press would
   * otherwise start a selection that flashes across the page (feedback round 40).
   */
  function noSelection(on) {
    document.body.classList.toggle("dragging", on);
    if (on && window.getSelection) window.getSelection().removeAllRanges();
  }
  document.addEventListener("pointerdown", (e) => {
    if (!e.target.closest || !e.target.closest(".axis.pickable") || S.busy) return;
    e.preventDefault();
    axisPointer = e.pointerId;
    noSelection(true);
    pickOnAxis(e.clientX);
  });
  document.addEventListener("pointermove", (e) => { if (e.pointerId === axisPointer) pickOnAxis(e.clientX); });
  const endAxis = (e) => {
    if (e.pointerId !== axisPointer) return;
    axisPointer = null;
    noSelection(false);
  };
  document.addEventListener("pointerup", endAxis);
  document.addEventListener("pointercancel", endAxis);

  // ---- A sheet follows a drag on its handle and closes when pulled down far enough (LY-01) ----
  let sheetDrag = null;
  document.addEventListener("pointerdown", (e) => {
    const handle = e.target.closest && e.target.closest("[data-handle]");
    if (!handle) return;
    sheetDrag = { id: e.pointerId, y: e.clientY, sheet: handle.closest(".sheet") };
  });
  document.addEventListener("pointermove", (e) => {
    if (!sheetDrag || e.pointerId !== sheetDrag.id) return;
    sheetDrag.sheet.style.transform = `translateY(${Math.max(0, e.clientY - sheetDrag.y)}px)`;
  });
  const endSheetDrag = (e) => {
    if (!sheetDrag || e.pointerId !== sheetDrag.id) return;
    const pulled = e.clientY - sheetDrag.y;
    sheetDrag.sheet.style.transform = "";
    sheetDrag = null;
    if (pulled > SHEET_CLOSE_PX) { S.overlay = null; S.afterDrag = true; setTimeout(() => { S.afterDrag = false; }, 400); render(); }
  };
  document.addEventListener("pointerup", endSheetDrag);
  document.addEventListener("pointercancel", endSheetDrag);
  const SHEET_CLOSE_PX = 80;

  // ---- Drag the guess along the timeline (feedback round 27) ----
  // Press a gap and drag: the chosen gap follows the finger and is sent as it changes, so the host sees
  // the drag too (feedback round 41). Gaps don't scroll the page (touch-action in the CSS), cards still
  // do; near the screen's edges the page scrolls.
  let drag = null;
  /** The gap to send next, and whether a send is on its way: one at a time, the newest gap wins. */
  const live = { slot: null, sending: false };
  function sendGap(slot) {
    live.slot = slot;
    pumpGap();
  }
  /** Resolves once every dragged gap has been sent and answered. */
  function dragSent() {
    return new Promise((resolve) => {
      const check = () => (live.sending || live.slot != null ? setTimeout(check, 25) : resolve());
      check();
    });
  }
  async function pumpGap() {
    const v = S.view;
    const g = v && v.game;
    if (live.sending || live.slot == null) return;
    if (!g || g.phase !== "PLACING" || g.activeId !== v.me) {
      live.slot = null;
      return;
    }
    const slot = live.slot;
    live.sending = true;
    let ok = false;
    try {
      ok = (await post("action", { client: CID, version: g.version, round: g.round, type: "dragSelect", slot })).ok;
    } catch (e) {
      ok = false;
    }
    live.sending = false;
    if (live.slot === slot) live.slot = null;
    // Not taken after the finger left: show the gap the host has.
    if (!ok && !drag && live.slot == null) { S.dragSlot = null; render(); }
    pumpGap();
  }
  function nearestGap(y) {
    let best = null;
    let distance = Infinity;
    document.querySelectorAll('.timeline [data-act="select"]').forEach((el) => {
      const r = el.getBoundingClientRect();
      const d = Math.abs(r.top + r.height / 2 - y);
      if (d < distance) { distance = d; best = Number(el.dataset.slot); }
    });
    return best;
  }
  function dragTo(y) {
    const slot = nearestGap(y);
    if (slot != null && slot !== S.dragSlot) {
      S.dragSlot = slot;
      render();
      sendGap(slot);
    }
  }
  document.addEventListener("pointerdown", (e) => {
    const gap = e.target.closest && e.target.closest('[data-act="select"]');
    if (gap && !S.busy) {
      drag = { id: e.pointerId, startY: e.clientY, y: e.clientY, timer: null };
      noSelection(true);
    }
  });
  document.addEventListener("pointermove", (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    drag.y = e.clientY;
    if (!drag.timer && Math.abs(e.clientY - drag.startY) < 8) return;
    if (!drag.timer) {
      // Near the top, or above the bar at the bottom, the page scrolls and the chosen gap with it.
      drag.timer = setInterval(() => {
        const bar = document.querySelector(".bottom");
        const bottomEdge = window.innerHeight - (bar ? bar.offsetHeight : 0);
        const speed = drag.y < 80 ? -(80 - drag.y) / 4 : drag.y > bottomEdge - 80 ? (drag.y - (bottomEdge - 80)) / 4 : 0;
        if (speed) window.scrollBy(0, speed);
        dragTo(drag.y);
      }, 16);
    }
    dragTo(e.clientY);
  });
  function endDrag(e, send) {
    if (!drag || e.pointerId !== drag.id) return;
    const moved = !!drag.timer;
    clearInterval(drag.timer);
    drag = null;
    noSelection(false);
    if (!moved) return;
    // The click that follows a drag is not a tap on the gap.
    S.afterDrag = true;
    setTimeout(() => { S.afterDrag = false; }, 400);
    // The last gap was sent while dragging; it stays shown until the host's view has it. A cancelled
    // drag goes back to the host's gap.
    const g = S.view && S.view.game;
    if (!send || (g && !live.sending && live.slot == null && g.selectedSlot === S.dragSlot)) {
      live.slot = null;
      S.dragSlot = null;
    }
    render();
  }
  document.addEventListener("pointerup", (e) => endDrag(e, true));
  document.addEventListener("pointercancel", (e) => endDrag(e, false));

  document.addEventListener("click", (e) => {
    if (S.afterDrag) { S.afterDrag = false; return; }
    const target = e.target.closest("[data-act]");
    if (!target) return;
    // Taps inside a sheet don't close it unless they hit a button.
    if (target.classList.contains("overlay") && e.target.closest("[data-stop]")) return;
    const a = target.dataset.act;
    const slot = target.dataset.slot != null ? Number(target.dataset.slot) : null;
    switch (a) {
      case "claim": claim(target.dataset.player); break;
      case "takeoverYes": { const t = S.takeover; S.takeover = null; if (t) claim(t.id, true); else render(); break; }
      case "takeoverNo": S.takeover = null; render(); break;
      case "select":
        // With "tap twice to confirm", tapping the chosen gap again locks in.
        if (confirmTwice() && S.view.game.selectedSlot === slot) act("lockIn");
        else act("select", { slot });
        break;
      case "lockIn": act("lockIn"); break;
      case "startVeto": S.vetoMode = true; act("vetoStart"); break;
      case "cancelVeto": S.vetoMode = false; S.pendingVeto = null; act("vetoCancel"); break;
      case "veto":
        // A tap marks the gap; tapping the marked gap again places it only with double-tap on.
        if (S.pendingVeto === slot && confirmTwice()) { S.pendingVeto = null; act("veto", { slot }); }
        else { S.pendingVeto = slot; render(); }
        break;
      case "confirmVeto":
        if (S.pendingVeto != null) { const pending = S.pendingVeto; S.pendingVeto = null; act("veto", { slot: pending }); }
        break;
      case "skip": act("skip"); break;
      case "buy": act("buy"); break;
      case "toggleConfirm": writeCookie("hl_confirm", confirmTwice() ? "0" : "1"); render(); break;
      case "toggleCovers": writeCookie("hl_covers", coversOn() ? "0" : "1"); render(); break;
      case "decline": act("decline"); break;
      case "togglePlay": act("togglePlay"); break;
      case "replay": act("replay"); break;
      case "digit":
        if (target.dataset.key === "back") S.digits = S.digits.slice(0, -1);
        else if (S.digits.length < 4) S.digits += target.dataset.key;
        render();
        break;
      case "guess": act("guess", { year: Number(S.digits) }); break;
      case "scores": S.adjusting = false; S.overlay = a; render(); break;
      case "menu": case "settings": case "cards": case "theme": S.overlay = a; render(); break;
      case "info": S.overlay = a; render(); break;
      case "toggleRules": S.rulesOpen = !S.rulesOpen; render(); break;
      case "adjustToggle": S.adjusting = !S.adjusting; render(); break;
      case "yearEdit": S.yearEdit = !S.yearEdit; S.holdRender = false; render(); break;
      case "switch": S.overlay = "pick"; render(); break;
      case "setTheme": writeCookie("hl_theme", target.dataset.theme || null); render(); break;
      case "close": S.overlay = null; render(); break;
      case "sound": enableSound(); render(); break;
      case "dismiss": S.dismissed.add(target.dataset.notice); render(); break;
      case "hostReveal": case "hostNext": case "hostUndo": case "hostWhole": act(a); break;
      case "removeAsk": S.removing = target.dataset.player; render(); break;
      case "removeNo": S.removing = null; render(); break;
      case "removeYes": { const id = S.removing; S.removing = null; S.expanded = null; if (id) act("hostRemove", { player: id }); else render(); break; }
      case "hostNamed": act("hostNamed", { player: target.dataset.player, named: target.getAttribute("aria-pressed") !== "true" }); break;
      case "hostAdjust": act("hostAdjust", { player: target.dataset.player, delta: Number(target.dataset.delta) }); break;
      case "hostYear": {
        const input = document.getElementById("host-year");
        const year = input ? Number(input.value) : NaN;
        S.holdRender = false;
        if (Number.isInteger(year)) { S.yearEdit = false; act("hostYear", { year }); }
        break;
      }
      case "volumeToggle": S.volumeOpen = !S.volumeOpen; render(); break;
      case "hostClip": act("hostClip", { year: Number(target.dataset.seconds) }); break;
      case "hostOffset": act("hostOffset", { year: Number(target.dataset.seconds) }); break;
      case "toggleGuesses": S.guessesOpen = S.guessesOpen === false; render(); break;
      case "expand": S.expanded = S.expanded === target.dataset.player ? null : target.dataset.player; render(); break;
      default: break;
    }
  });

  /** Closest Year: digits, Backspace and Enter from a keyboard work like the keypad (feedback round 13). */
  document.addEventListener("keydown", (e) => {
    const v = S.view;
    const g = v && v.game;
    if (!g || v.paused || S.overlay || !guessTurn(g, v.me)) return;
    if (/^[0-9]$/.test(e.key)) {
      if (S.digits.length < 4) S.digits += e.key;
    } else if (e.key === "Backspace") {
      S.digits = S.digits.slice(0, -1);
    } else if (e.key === "Enter") {
      if (S.digits.length === 4) act("guess", { year: Number(S.digits) });
      e.preventDefault();
      return;
    } else {
      return;
    }
    e.preventDefault();
    render();
  });

  // ---- Start ----

  async function start() {
    if (INTERNET) {
      // Loaded only for internet invites: the Wi-Fi page never touches the public relays.
      const { p2pTransport } = await import("./p2p.js");
      transport = p2pTransport({
        strategy: INTERNET[1], roomId: INTERNET[2], secret: INTERNET[3], hostKey: INTERNET[4], clientId: CID,
        onImage: () => render(),
        onStream: (stream) => Music.setStream(stream),
      });
      setInterval(Music.syncClock, 15_000);
    } else {
      transport = httpTransport(KEY, CID);
    }
    try {
      S.strings = await transport.strings();
    } catch (e) {
      S.strings = {};
    }
    render();
    if (!transport.valid || (await transport.check()) === "invalid") {
      S.invalid = true;
      render();
      return;
    }
    connect();
  }

  start();
})();
