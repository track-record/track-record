package com.trackrecord.app.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.trackrecord.data.lists.SongListParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Imports Exportify's downloads by themselves (feedback round 32), when the app may read the Download
 * folder: "All files access" from Android 11 on, the storage permission before. From [start] (Exportify
 * opened) for [WATCH_MS], the folder is checked every [POLL_MS], also while the browser is in front
 * (feedback round 33), and every new Exportify CSV or ZIP is imported once it has finished downloading. Then the
 * watch ends and [import] brings the app back, which closes the browser (feedback round 34). Without the permission nothing happens and the
 * user opens the file with the app, as before.
 */
class ExportifyWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The importer is still busy with an earlier list: new ones wait. */
    private val busy: () -> Boolean,
    private val import: (List<Pair<String, String>>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null
    private val seen = HashSet<String>()

    /** Sizes seen at the last check: a file counts as downloaded once its size stays the same. */
    private val sizes = HashMap<String, Long>()
    private val waiting = mutableListOf<Pair<String, String>>()

    /** Lists imported from Download, so opening the same file from the download message adds nothing. */
    private val imported = HashSet<Int>()

    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Exportify opens now: files downloaded from here on are imported. */
    fun start() {
        val since = clock() - SLACK_MS
        job?.cancel()
        job = scope.launch {
            while (isActive && clock() - since < WATCH_MS) {
                if (check(since)) break
                delay(POLL_MS)
            }
        }
    }

    /** True once lists were handed to [import]. */
    private fun check(since: Long): Boolean {
        if (!hasAccess()) return false
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidates = dir.listFiles().orEmpty().filter { f ->
            f.isFile && f.lastModified() >= since && f.extension.lowercase() in TYPES && key(f) !in seen
        }
        for (f in candidates) {
            val size = f.length()
            // Still being written while its size changes; complete once it stays the same.
            if (size == 0L || sizes.put(f.path, size) != size) continue
            seen += key(f)
            // Only Exportify's lists: another CSV or ZIP downloaded meanwhile stays where it is (REVIEW-2 B-13; D-268).
            val lists = readSongLists(f).filter { SongListParser.isExportify(it.second) }
            synchronized(imported) { lists.forEach { imported += it.second.hashCode() } }
            waiting += lists
        }
        if (waiting.isEmpty() || busy()) return false
        import(waiting.toList())
        waiting.clear()
        return true
    }

    /** [lists] without those already imported from Download. */
    fun notYetImported(lists: List<Pair<String, String>>): List<Pair<String, String>> =
        synchronized(imported) { lists.filter { it.second.hashCode() !in imported } }

    private fun key(f: File) = "${f.name}|${f.lastModified()}"

    private companion object {
        val TYPES = setOf("csv", "zip")

        /** Watches this long after Exportify opened. */
        const val WATCH_MS = 30 * 60_000L

        /** How often Download is checked meanwhile: about three times a second, so the import starts at once (feedback round 33). */
        const val POLL_MS = 350L

        /** File times are coarse on some storage. */
        const val SLACK_MS = 5_000L
    }
}
