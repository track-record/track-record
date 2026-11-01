// The desktop's camera for players' photos (feedback round 55): the webcam through getUserMedia, small JPEG frames to
// the app about ten times a second for its preview, and one at full size when the photo is taken. Frames come from
// the track itself (ImageCapture), as this page is never shown.
// Commands: take. Answers: frame {data}, photo {data}, failed {reason}. The camera starts when the page opens and
// stops when the app closes it.
let port = null;
let capture = null;
const canvas = document.createElement("canvas");

function toApp(message) {
  if (port) port.postMessage(JSON.stringify(message));
}

/** The track's current frame as JPEG (base64), at most [maxWidth] wide. */
async function jpeg(maxWidth, quality) {
  const bitmap = await capture.grabFrame();
  const scale = Math.min(1, maxWidth / bitmap.width);
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const url = canvas.toDataURL("image/jpeg", quality);
  return url.substring(url.indexOf(",") + 1);
}

async function start() {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 960 } }, audio: false });
  } catch (e) {
    toApp({ t: "failed", reason: e.name });
    return;
  }
  capture = new ImageCapture(stream.getVideoTracks()[0]);
  // One frame at a time: the next is asked for once the last went out.
  const preview = async () => {
    try { toApp({ t: "frame", data: await jpeg(PREVIEW_WIDTH, 0.6) }); } catch (e) { /* the camera is still starting */ }
    setTimeout(preview, 100);
  };
  preview();
}

const PREVIEW_WIDTH = 480;

window.addEventListener("message", (event) => {
  if (event.data !== "trackrecord-port" || !event.ports || !event.ports[0]) return;
  port = event.ports[0];
  port.onmessage = async (m) => {
    let command;
    try { command = JSON.parse(m.data); } catch (e) { return; }
    if (command.t === "take" && capture) {
      try { toApp({ t: "photo", data: await jpeg(4096, 0.92) }); } catch (e) { toApp({ t: "failed", reason: e.name }); }
    }
  };
  start();
});
