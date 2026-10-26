// Track Record guest page, the Wi-Fi transport (SPEC v3, v4): HTTP to the phone's server on the same
// Wi-Fi, the key in ?k=, the live view as Server-Sent Events. Split from app.js (REVIEW.md C-05; D-274);
// the internet one is p2p.js. A transport has: valid, strings(), check() → "ok" | "invalid" |
// "unreachable", post(path, body), image(path) → address or null, connect(handlers) and closed().

export function httpTransport(key, clientId) {
  const url = (path) => `api/${path}${path.includes("?") ? "&" : "?"}k=${encodeURIComponent(key)}`;
  let es = null;
  const t = {
    valid: !!key,
    async strings() {
      const response = await fetch("api/strings");
      return response.json();
    },
    async time() {
      const response = await fetch(url("time"));
      return (await response.json()).now;
    },
    async check() {
      try {
        const response = await fetch(url("check"));
        return response.status === 403 ? "invalid" : "ok";
      } catch (e) {
        return "unreachable";
      }
    },
    async post(path, body) {
      const response = await fetch(url(path), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      return response.json();
    },
    image: (path) => url(path),
    /** handlers: onView(view), onOpen(), onLost(), onInvalid(). Reconnects by itself. */
    connect(handlers) {
      if (es) es.close();
      es = new EventSource(url(`events?c=${clientId}`));
      const mine = es;
      es.addEventListener("view", (e) => handlers.onView(JSON.parse(e.data)));
      es.onopen = () => handlers.onOpen();
      es.onerror = () => {
        handlers.onLost();
        // A refused stream isn't retried by the browser: check whether the link is still valid.
        if (mine.readyState === EventSource.CLOSED) {
          t.check().then((r) => { if (r === "invalid") handlers.onInvalid(); else if (es === mine) setTimeout(() => t.connect(handlers), 3000); });
        }
      };
    },
    closed: () => !es || es.readyState === EventSource.CLOSED,
  };
  return t;
}
