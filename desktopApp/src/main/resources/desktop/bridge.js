// The desktop app's stand-in for Android's private message port (PLATFORMS.md phase 0): the page gets
// one end of a MessageChannel under the same "trackrecord-port" message, the other end talks to the
// app. Page to app: one message-router query per message. App to page: the app calls
// trackRecordBridge(text) (JCEF answers a persistent query only once), after the "open" query.
(() => {
  const channel = new MessageChannel();
  const app = channel.port1;
  app.onmessage = (m) => window.cefQuery({ request: "m" + m.data, onSuccess() {}, onFailure() {} });
  window.trackRecordBridge = (text) => app.postMessage(text);
  window.cefQuery({ request: "open", onSuccess() {}, onFailure() {} });
  window.addEventListener("load", () => window.postMessage("trackrecord-port", location.origin, [channel.port2]));
})();
