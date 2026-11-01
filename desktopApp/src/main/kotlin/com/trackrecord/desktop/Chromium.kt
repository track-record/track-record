package com.trackrecord.desktop

import com.jetbrains.cef.JCefAppConfig
import kotlinx.serialization.json.JsonPrimitive
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRendering
import org.cef.callback.CefCallback
import org.cef.callback.CefCommandLine
import org.cef.callback.CefDragData
import org.cef.callback.CefMediaAccessCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefAppHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.handler.CefScreenInfo
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.awt.Point
import java.awt.Rectangle
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.swing.JPanel

/** A file served to the hidden pages. */
class Served(val status: Int, val mime: String, val input: InputStream?, val length: Long, val headers: Map<String, String> = emptyMap())

/**
 * Chromium in the app (PLATFORMS.md phases 0 and 6): the JBR's JCEF, one client, hidden browsers that are never
 * painted, and one private origin ([ORIGIN]) that only this app serves: /assets/ from the bundled web pages
 * (web/remote, web/p2p-host and the desktop's own in desktop/), /song/ the local files the app [publish]es, AAC as WAV
 * ([AacToWav]: Chromium has no AAC decoder). Chromium's console goes to the log in development runs ([verbose]);
 * [debugPort] opens DevTools there (0: none).
 */
class Chromium(cacheDir: File, debugPort: Int, mute: Boolean, private val verbose: Boolean) {
    private val ready = CountDownLatch(1)
    private val app: CefApp
    private val client: CefClient
    private val router: CefMessageRouter

    /** Each hidden browser's port to the app. */
    private val ports = ConcurrentHashMap<CefBrowser, PagePort>()

    /** Local files the pages may load, by their /song/ path: only those the app published. */
    private val files = ConcurrentHashMap<String, File>()

    private val aac = AacToWav(File(cacheDir, "decoded"))

    init {
        val config = JCefAppConfig.getInstance()
        val args = config.appArgs
        val settings: CefSettings = config.cefSettings.apply {
            windowless_rendering_enabled = true
            cache_path = cacheDir.absolutePath
            log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
            log_file = File(cacheDir, "cef.log").absolutePath
            // The one language the package keeps (desktopApp/build.gradle.kts): the app shows none of Chromium's pages.
            locale = "en-US"
            if (debugPort > 0) remote_debugging_port = debugPort
        }
        CefApp.addAppHandler(object : CefAppHandlerAdapter(args) {
            override fun onBeforeCommandLineProcessing(processType: String?, commandLine: CefCommandLine) {
                // The host's player starts without a click, as ExoPlayer does on Android.
                commandLine.appendSwitchWithValue("autoplay-policy", "no-user-gesture-required")
                if (mute) commandLine.appendSwitch("mute-audio")
                // Only the app's own pages and requests (D-359): Chromium otherwise installed the extensions other
                // programs register for Chrome in the registry (Adobe's, from the Chrome Web Store) and ran its
                // background services (component updates and the like).
                for (switch in QUIET) commandLine.appendSwitch(switch)
            }

            override fun onContextInitialized() = ready.countDown()
        })
        check(CefApp.startup(args)) { "JCEF didn't start" }
        app = CefApp.getInstance(args, settings)
        ready.await()
        app.registerSchemeHandlerFactory("https", HOST) { _, _, _, request ->
            // What the request asks is read here: CEF's request object belongs to this call.
            val path = request.url.removePrefix(ORIGIN).substringBefore('?')
            val range = request.getHeaderByName(RANGE)
            Resource { serve(path, range) }
        }
        client = app.createClient()
        router = CefMessageRouter.create()
        router.addHandler(
            object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(browser: CefBrowser, frame: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean {
                    // Only our own pages talk to the app.
                    if (!frame.url.startsWith(ORIGIN)) return false
                    val port = ports[browser] ?: return false
                    when {
                        request == "open" -> {
                            port.opened(browser)
                            callback.success("")
                        }
                        request.startsWith("m") -> {
                            port.received(request.substring(1))
                            callback.success("")
                        }
                        else -> return false
                    }
                    return true
                }
            },
            true,
        )
        client.addMessageRouter(router)
        // The camera, for players' photos: only our camera page gets it, and nothing gets the microphone or the screen.
        client.addPermissionHandler { _, frame, origin, permissions, callback ->
            val camera = CefMediaAccessCallback.MediaPermissionFlags.DEVICE_VIDEO_CAPTURE
            if (origin.startsWith(ORIGIN) && frame?.url?.startsWith("$ORIGIN/assets/desktop/camera.html") == true && permissions == camera) {
                callback.Continue(camera)
            } else {
                callback.Cancel()
            }
            true
        }
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            // The hidden pages never navigate anywhere.
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean) =
                !request.url.startsWith(ORIGIN)

            override fun getResourceRequestHandler(
                browser: CefBrowser?, frame: CefFrame?, request: CefRequest, isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?, disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler? = if (request.url.startsWith("$ORIGIN/song/")) MovesRange else null
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (verbose) Log.i("loaded ${frame.url} $httpStatusCode")
            }

            override fun onLoadError(browser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String) =
                Log.w("load error $failedUrl $errorCode $errorText")
        })
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(browser: CefBrowser, level: CefSettings.LogSeverity, message: String, source: String, line: Int): Boolean {
                if (verbose) Log.i("console ${source.substringAfterLast('/')}:$line $message")
                return true
            }
        })
    }

    /** Opens [path] (under /assets/) in a hidden browser; [port] connects it to the app. */
    fun open(path: String, port: PagePort): CefBrowser {
        val browser = client.createBrowser("$ORIGIN/assets/$path", CefRendering.CefRenderingWithHandler(NoPaint, JPanel()), false)
        ports[browser] = port
        browser.createImmediately()
        return browser
    }

    /** Makes [file] loadable by the pages, at an address nobody can guess; returns it. */
    fun publish(file: File): String {
        val path = "/song/${UUID.randomUUID()}"
        files[path] = file
        return ORIGIN + path
    }

    /** No longer loadable at [address] (from [publish]). */
    fun unpublish(address: String) {
        files.remove(address.removePrefix(ORIGIN))
    }

    /** A published file as Chromium can decode it: AAC decoded to WAV first (feedback round 55). */
    private fun playable(file: File): File? = if (aac.needed(file)) aac.wavOf(file) else file

    /** A local file with byte ranges, so the page's player can seek. */
    private fun serveFile(file: File, range: String?): Served {
        if (!file.isFile) return Served(404, "text/plain", null, 0)
        val length = file.length()
        val from = range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (from != null && from >= length) return Served(416, "text/plain", null, 0, mapOf("Content-Range" to "bytes */$length"))
        val raf = RandomAccessFile(file, "r")
        if (from == null) return Served(200, mime(file.name), Channels.newInputStream(raf.channel), length, mapOf("Accept-Ranges" to "bytes"))
        raf.seek(from)
        return Served(
            206, mime(file.name), Channels.newInputStream(raf.channel), length - from,
            mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes $from-${length - 1}/$length"),
        )
    }

    /** Closes a page opened with [open]. */
    fun close(browser: CefBrowser) {
        ports.remove(browser)
        browser.close(true)
    }

    fun close() {
        client.dispose()
        app.dispose()
    }

    private fun serve(path: String, range: String?): Served {
        return when {
            path.startsWith("/song/") -> files[path]?.let(::playable)?.let { serveFile(it, range) } ?: Served(404, "text/plain", null, 0)
            path.startsWith("/assets/") -> {
                val name = path.removePrefix("/assets/")
                val bytes = if (".." in name) null else Chromium::class.java.getResourceAsStream("/$name")?.use { it.readBytes() }
                if (bytes == null) Served(404, "text/plain", null, 0) else Served(200, mime(name), bytes.inputStream(), bytes.size.toLong())
            }
            else -> Served(404, "text/plain", null, 0)
        }
    }

    /** A response made off CEF's IO thread by [make]: decoding an AAC file takes a moment. */
    private class Resource(private val make: () -> Served) : CefResourceHandler {
        // Made on SERVING's thread, read on CEF's.
        @Volatile private var served = Served(500, "text/plain", null, 0)

        override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
            SERVING.execute {
                served = runCatching(make).getOrElse { Served(500, "text/plain", null, 0) }
                callback.Continue()
            }
            return true
        }

        override fun getResponseHeaders(response: CefResponse, length: IntRef, redirect: StringRef) {
            response.status = served.status
            response.mimeType = served.mime
            response.setHeaderMap(served.headers)
            length.set(if (served.input == null) 0 else served.length.toInt())
        }

        override fun readResponse(buffer: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
            val n = served.input?.read(buffer, 0, bytesToRead) ?: -1
            if (n <= 0) {
                served.input?.close()
                bytesRead.set(0)
                return false
            }
            bytesRead.set(n)
            return true
        }

        override fun cancel() {
            served.input?.close()
        }
    }

    /**
     * JCEF's resource handlers can't skip (CefResourceHandler::Skip fails), so CEF fails every request for a
     * range that doesn't start at 0. The range moves to [RANGE] before CEF sees it, and the file is served
     * from there.
     */
    private object MovesRange : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): Boolean {
            val headers = HashMap<String, String>()
            request.getHeaderMap(headers)
            val range = headers.keys.firstOrNull { it.equals("Range", ignoreCase = true) } ?: return false
            headers[RANGE] = headers.remove(range)!!
            request.setHeaderMap(headers)
            return false
        }
    }

    /** A browser that is never shown: Chromium renders nothing worth painting. */
    private object NoPaint : CefRenderHandler {
        override fun getViewRect(browser: CefBrowser) = Rectangle(0, 0, 16, 16)
        override fun getScreenInfo(browser: CefBrowser, screenInfo: CefScreenInfo) = false
        override fun getScreenPoint(browser: CefBrowser, viewPoint: Point) = viewPoint
        override fun getDeviceScaleFactor(browser: CefBrowser) = 1.0
        override fun onPopupShow(browser: CefBrowser, show: Boolean) = Unit
        override fun onPopupSize(browser: CefBrowser, size: Rectangle) = Unit
        override fun onPaint(browser: CefBrowser, popup: Boolean, dirtyRects: Array<out Rectangle>, buffer: ByteBuffer, width: Int, height: Int) = Unit
        override fun onCursorChange(browser: CefBrowser, cursorType: Int) = false
        override fun startDragging(browser: CefBrowser, dragData: CefDragData, mask: Int, x: Int, y: Int) = false
        override fun updateDragCursor(browser: CefBrowser, operation: Int) = Unit
    }

    companion object {
        /** Where the responses are made. */
        private val SERVING = Executors.newCachedThreadPool { r -> Thread(r, "chromium-serve").apply { isDaemon = true } }

        const val HOST = "app.trackrecord.local"
        const val ORIGIN = "https://$HOST"
        private const val RANGE = "X-TrackRecord-Range"

        /** Chromium's switches for no extensions and no background requests of its own. */
        private val QUIET = listOf(
            "disable-extensions",
            "disable-component-extensions-with-background-pages",
            "disable-component-update",
            "disable-background-networking",
            "disable-default-apps",
            "disable-sync",
            "disable-domain-reliability",
            "no-first-run",
            "no-default-browser-check",
        )

        fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}

/**
 * One page's connection to the app (bridge.js): [send] goes to the page, [onMessage] gets what it sends,
 * off Chromium's thread. Messages sent before the page's bridge is up wait.
 */
class PagePort(private val name: String, private val onMessage: (String) -> Unit) {
    private val inbox = Executors.newSingleThreadExecutor { r -> Thread(r, "$name-inbox").apply { isDaemon = true } }
    private var browser: CefBrowser? = null
    private val waiting = ArrayList<String>()

    @Synchronized
    fun send(text: String) {
        val b = browser
        if (b == null) waiting += text else deliver(b, text)
    }

    @Synchronized
    internal fun opened(b: CefBrowser) {
        browser = b
        waiting.forEach { deliver(b, it) }
        waiting.clear()
    }

    /** As a JSON string literal, which is also a JavaScript one. */
    private fun deliver(b: CefBrowser, text: String) = b.executeJavaScript("trackRecordBridge(${JsonPrimitive(text)})", Chromium.ORIGIN, 0)

    internal fun received(text: String) {
        inbox.execute { onMessage(text) }
    }
}
