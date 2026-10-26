package com.trackrecord.app.remote

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.trackrecord.app.BuildConfig
import com.trackrecord.playback.PlaybackFocus
import com.trackrecord.remote.ListenInfo
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The host's internet room (SPEC v4; DECISIONS.md D-180): a WebView that is never shown runs
 * assets/p2p-host/room.js, which gives the app WebRTC without a native library. Guests connect to it
 * directly; their messages come out here through a private message port (only our page holds it), and
 * [onMessage] / [onLeave] hand them to the tunnel, off the main thread (D-261). Lives until [close].
 */
class InternetRoom(
    private val context: Context,
    private val onMessage: (peer: String, text: String) -> Unit,
    private val onLeave: (peer: String) -> Unit,
) {
    val roomId: String = randomId(9)
    val secret: String = randomId(16)

    /**
     * The host's key (D-260): the room signs each guest's challenge with it, bound to that connection,
     * and guests accept only a host whose public key matches [hostKeyId] from the link. Everyone with the
     * link knows the secret; only this phone holds the key.
     */
    private val hostKey: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val hostKeyId: String = keyId(hostKey.public.encoded)

    /** The invite link: the published guest page, the room, the secret and the host's key id in the fragment. */
    val link: String get() = "${BuildConfig.GUEST_PAGE}#n.$roomId.$secret.$hostKeyId"

    private val main = Handler(Looper.getMainLooper())

    /** Guests' messages are parsed here, never on the main thread (D-261). */
    private val inbox = HandlerThread("room-inbox").apply { start() }
    private var webView: WebView? = null
    private var port: WebMessagePortCompat? = null

    /** The local file the room streams to internet guests (SPEC v4; D-181), served at /song/<n>. */
    @Volatile private var songUri: String? = null
    @Volatile private var songVersion = 0
    private var lastFollow: String? = null

    /** Messages for the page before its port is ready. */
    private val waiting = ArrayList<String>()
    private var closed = false

    /** Guests connected now, so a page that died can let them go (D-263). */
    private val peers = HashSet<String>()
    private var restarts = 0

    @SuppressLint("SetJavaScriptEnabled")
    fun open() = main.post {
        if (closed || webView != null) return@post
        if (!supported()) {
            Log.w(TAG, "this WebView can't run the internet room")
            return@post
        }
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            // For streaming local files to guests (v4 milestone 3).
            settings.mediaPlaybackRequiresUserGesture = false
            // Only the asset loader and /song/ serve this page (REVIEW-2 S-15).
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN && request.url.path.orEmpty().startsWith("/song/")) {
                        serveSong(request)
                    } else {
                        assets.shouldInterceptRequest(request.url)
                    }

                // The room never navigates anywhere.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun onPageFinished(view: WebView, url: String) = connectPort(view)

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    restart(view)
                    return true
                }
            }
            loadUrl("$ORIGIN/assets/p2p-host/room.html")
        }
    }

    /**
     * The room's page died (a crash, or Android needed the memory): it never takes the app with it
     * (D-263). Its guests are let go, and a new page opens the same room with the same secret and key,
     * where the guests' pages find it again by themselves.
     */
    private fun restart(dead: WebView) {
        if (webView !== dead) return
        Log.w(TAG, "the room's page stopped; opening it again")
        port?.close()
        port = null
        webView = null
        dead.destroy()
        val gone = synchronized(peers) { peers.toList().also { peers.clear() } }
        gone.forEach(onLeave)
        waiting.clear()
        lastFollow?.let { waiting += it }
        if (++restarts <= MAX_RESTARTS) main.postDelayed({ open() }, RESTART_DELAY_MS)
    }

    /**
     * What the host plays (SPEC v4; D-181): for a local file the room plays it silently, in step with
     * the host's player, and streams it to the internet guests; otherwise it stays quiet (a Deezer
     * preview is played by the guests themselves).
     */
    @Synchronized
    fun follow(listen: ListenInfo?, uri: String?) {
        val stream = listen?.takeIf { it.kind == "stream" && uri != null }
        PlaybackFocus.sharedWithRoom.value = stream != null
        if (stream != null && uri != songUri) {
            songUri = uri
            songVersion++
        }
        val message = JSONObject().put("t", "audio")
        if (stream == null) {
            message.put("src", JSONObject.NULL)
        } else {
            message.put("src", "$ORIGIN/song/$songVersion").put("playing", stream.playing).put("positionMs", stream.positionMs).put("atMs", stream.atMs)
        }
        val text = message.toString()
        if (text == lastFollow) return
        lastFollow = text
        post(text)
    }

    /**
     * The current song's file for the room's player, with byte ranges so it can seek. Only the song
     * the host plays right now is ever served, at its own address; the WebView is the only client.
     */
    private fun serveSong(request: WebResourceRequest): WebResourceResponse {
        val uri = songUri?.takeIf { request.url.path == "/song/$songVersion" }?.let(Uri::parse) ?: return status(404, "Not Found")
        val resolver = context.contentResolver
        val length = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 }
        val type = resolver.getType(uri) ?: "audio/mpeg"
        val range = Regex("""bytes=(\d+)-""").find(request.requestHeaders["Range"] ?: request.requestHeaders["range"].orEmpty())?.groupValues?.get(1)?.toLongOrNull()
        if (range != null && length != null && range >= length) {
            return status(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$length"))
        }
        val input = runCatching { resolver.openInputStream(uri) }.getOrNull() ?: return status(404, "Not Found")
        if (range == null || length == null) {
            val headers = mutableMapOf("Accept-Ranges" to "bytes")
            if (length != null) headers["Content-Length"] = length.toString()
            return WebResourceResponse(type, null, 200, "OK", headers, input)
        }
        var skipped = 0L
        while (skipped < range) {
            val n = input.skip(range - skipped)
            if (n <= 0) break
            skipped += n
        }
        val headers = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $range-${length - 1}/$length",
            "Content-Length" to (length - range).toString(),
        )
        return WebResourceResponse(type, null, 206, "Partial Content", headers, input)
    }

    private fun status(code: Int, reason: String, headers: Map<String, String> = emptyMap()) =
        WebResourceResponse("text/plain", "utf-8", code, reason, headers, ByteArrayInputStream(ByteArray(0)))

    /** Sends [text] to guest [peer]. Any thread. */
    fun send(peer: String, text: String) = post(JSONObject().put("t", "send").put("peer", peer).put("data", text).toString())

    fun close() = main.post {
        if (closed) return@post
        closed = true
        PlaybackFocus.sharedWithRoom.value = false
        port?.postMessage(WebMessageCompat(JSONObject().put("t", "leave").toString()))
        port?.close()
        port = null
        webView?.destroy()
        webView = null
        waiting.clear()
        inbox.quitSafely()
    }

    private fun post(message: String) = main.post {
        val p = port
        if (closed) return@post
        // Nothing waits for a page that can't open (REVIEW-2 S-15).
        if (p == null) {
            if (webView != null || restarts in 1..MAX_RESTARTS) waiting += message
        } else {
            p.postMessage(WebMessageCompat(message))
        }
    }

    /** Hands the page one end of a private channel, then asks it to join the room. */
    private fun connectPort(view: WebView) {
        if (closed || port != null || view !== webView) return
        val (mine, theirs) = WebViewCompat.createWebMessageChannel(view)
        mine.setWebMessageCallback(
            Handler(inbox.looper),
            object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                    val text = message?.data ?: return
                    val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (m.optString("t")) {
                        "msg" -> onMessage(m.optString("peer"), m.optString("data"))
                        "left" -> {
                            val peer = m.optString("peer")
                            synchronized(peers) { peers -= peer }
                            onLeave(peer)
                        }
                        "joined" -> {
                            synchronized(peers) { peers += m.optString("peer") }
                            if (BuildConfig.DEBUG) Log.i(TAG, "guest ${m.optString("peer")} via ${m.optString("via")}")
                        }
                        "log" -> if (BuildConfig.DEBUG) Log.i(TAG, m.optString("text"))
                    }
                }
            },
        )
        // Only our page (the asset origin) receives the port.
        WebViewCompat.postWebMessage(view, WebMessageCompat("trackrecord-port", arrayOf(theirs)), Uri.parse(ORIGIN))
        port = mine
        val join = JSONObject().put("t", "join").put("room", roomId).put("secret", secret)
            .put("key", b64(hostKey.private.encoded)).put("pub", b64(hostKey.public.encoded))
        mine.postMessage(WebMessageCompat(join.toString()))
        waiting.forEach { mine.postMessage(WebMessageCompat(it)) }
        waiting.clear()
    }

    companion object {
        private const val TAG = "TrackRecordRoom"
        private const val ORIGIN = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}"
        private const val MAX_RESTARTS = 5
        private const val RESTART_DELAY_MS = 1_000L

        fun supported(): Boolean = listOf(
            WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL,
            WebViewFeature.POST_WEB_MESSAGE,
            WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK,
            WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE,
            WebViewFeature.WEB_MESSAGE_PORT_CLOSE,
        ).all(WebViewFeature::isFeatureSupported)

        /** The link's host key id: the first 128 bits of the public key's SHA-256 (SubjectPublicKeyInfo), as p2p-common.js computes it. */
        fun keyId(spki: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki).copyOf(16))

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun randomId(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    }
}
