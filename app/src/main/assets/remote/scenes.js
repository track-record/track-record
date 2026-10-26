// The Low Poly theme's background scene, redrawn for the browser from the app's Compose
// drawings (ui/theme/*.kt). Same shapes and colours; the random details come from another generator
// (DECISIONS.md D-107).
"use strict";

const Scenes = (() => {
  function rng(seed) {
    let a = seed >>> 0;
    return () => {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  // ---- Low Poly ----
  const polyStops = [[0, "#141A3A"], [0.35, "#2B2F6B"], [0.62, "#1F6F8B"], [0.85, "#2FA39A"], [1, "#E0B34A"]];
  const polyCols = 8, polyRows = 15;
  const polyPoints = (() => {
    const r = rng(21);
    return Array.from({ length: polyRows }, (_, y) => Array.from({ length: polyCols }, (_, x) => {
      const jx = x === 0 || x === polyCols - 1 ? 0 : (r() - 0.5) * 0.7;
      const jy = y === 0 || y === polyRows - 1 ? 0 : (r() - 0.5) * 0.7;
      return [(x + jx) / (polyCols - 1), (y + jy) / (polyRows - 1)];
    }));
  })();
  const polyLight = (() => { const r = rng(5); return Array.from({ length: polyRows * polyCols * 2 }, () => 0.82 + r() * 0.32); })();

  function hexRgb(hex) {
    const n = parseInt(hex.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  function gradientAt(t) {
    let i = 0;
    while (i < polyStops.length - 2 && polyStops[i + 1][0] <= t) i++;
    const [a, ca] = polyStops[i], [b, cb] = polyStops[i + 1];
    const f = Math.min(1, Math.max(0, (t - a) / (b - a)));
    const x = hexRgb(ca), y = hexRgb(cb);
    return x.map((v, k) => v + (y[k] - v) * f);
  }

  function lowPoly(ctx, w, h) {
    let n = 0;
    for (let y = 0; y < polyRows - 1; y++) for (let x = 0; x < polyCols - 1; x++) {
      const p00 = polyPoints[y][x], p10 = polyPoints[y][x + 1], p01 = polyPoints[y + 1][x], p11 = polyPoints[y + 1][x + 1];
      for (const tri of [[p00, p10, p11], [p00, p11, p01]]) {
        const cy = (tri[0][1] + tri[1][1] + tri[2][1]) / 3, cx = (tri[0][0] + tri[1][0] + tri[2][0]) / 3;
        const f = polyLight[n++];
        const c = gradientAt(Math.min(1, Math.max(0, cy * 0.85 + cx * 0.15))).map((v) => Math.min(255, Math.round(v * f)));
        const color = `rgb(${c[0]},${c[1]},${c[2]})`;
        ctx.beginPath();
        ctx.moveTo(tri[0][0] * w, tri[0][1] * h);
        ctx.lineTo(tri[1][0] * w, tri[1][1] * h);
        ctx.lineTo(tri[2][0] * w, tri[2][1] * h);
        ctx.closePath();
        ctx.fillStyle = color; ctx.fill();
        ctx.strokeStyle = color; ctx.lineWidth = 0.6; ctx.stroke(); // no hairline seams
      }
    }
  }

  const all = { LOW_POLY: lowPoly };

  /** Draws the scene of [theme] on [canvas] at the window's size; returns false for themes without one. */
  function draw(canvas, theme) {
    const scene = all[theme];
    canvas.hidden = !scene;
    if (!scene) return false;
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const w = window.innerWidth, h = window.innerHeight;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    scene(ctx, w, h);
    return true;
  }

  return { draw };
})();
