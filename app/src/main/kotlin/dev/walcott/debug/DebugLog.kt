package dev.walcott.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** One line in the in-app debug log. Pure (no Android deps), so the buffer logic is unit-testable. */
@Serializable
data class LogEntry(val epochMillis: Long, val level: Char, val tag: String, val message: String)

/**
 * Pure buffer/format/serialization helpers, kept free of Android so they can be unit-tested.
 * [DebugLog] wires these to a StateFlow, Logcat and a capped file. On-disk format is JSON Lines
 * (one entry per physical line), so multi-line messages (stack traces) can't corrupt parsing.
 */
internal object LogFormat {
    private val json = Json { ignoreUnknownKeys = true }
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    /** Appends [entry], trimming from the front so the list never exceeds [max]. */
    fun cap(entries: List<LogEntry>, entry: LogEntry, max: Int): List<LogEntry> {
        val next = entries + entry
        return if (next.size <= max) next else next.subList(next.size - max, next.size)
    }

    fun line(e: LogEntry): String =
        "${TIME.format(Instant.ofEpochMilli(e.epochMillis))} ${e.level}/${e.tag}: ${e.message}"

    fun format(entries: List<LogEntry>): String = entries.joinToString("\n") { line(it) }

    fun serialize(e: LogEntry): String = json.encodeToString(LogEntry.serializer(), e)

    /**
     * The newest [lines] that fit in [maxBytes], oldest first, and never more than [maxLines].
     *
     * Bounded by BYTES, because the file is bounded by bytes: trimming to a line count under a
     * byte trigger stops working the moment the lines are big. A stack trace serialises to a few
     * kilobytes, so a window holding a handful of them is over the byte cap at any count — and
     * the old trim then re-read and re-wrote the whole file on every single append, for good.
     */
    fun trimToBytes(lines: List<String>, maxBytes: Int, maxLines: Int): List<String> {
        var bytes = 0
        var kept = 0
        for (i in lines.indices.reversed()) {
            val size = lines[i].toByteArray(Charsets.UTF_8).size + 1
            if (kept == maxLines || bytes + size > maxBytes) break
            bytes += size
            kept++
        }
        return lines.subList(lines.size - kept, lines.size)
    }

    fun deserialize(line: String): LogEntry? =
        runCatching { json.decodeFromString(LogEntry.serializer(), line) }.getOrNull()
}

/**
 * Process-wide, in-app debug log. Same singleton style as [dev.walcott.update.UpdateCenter]:
 * writers ([dev.walcott.update.Updater], receivers, enforcement) call [i]/[w]/[e]; the debug
 * screen reads [entries]. Everything is mirrored to Logcat, and persisted to a small capped
 * file so traces survive the process restart a successful self-update triggers.
 */
object DebugLog {
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 128 * 1024
    private const val MAX_ENTRY_CHARS = 4_000
    private const val FILE_NAME = "debug-log.txt"

    private val mutable = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = mutable

    // Serializes all file I/O off the main thread; the UI only ever reads the in-memory flow.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "walcott-debuglog").apply { isDaemon = true }
    }
    @Volatile private var file: File? = null

    /** Loads any persisted tail into memory. Call once from [android.app.Application.onCreate]. */
    fun init(context: Context) {
        val f = File(context.filesDir, FILE_NAME)
        file = f
        io.execute {
            val loaded = runCatching { readTail(f) }.getOrDefault(emptyList())
            if (loaded.isNotEmpty() && mutable.value.isEmpty()) mutable.value = loaded
        }
    }

    fun i(tag: String, message: String) = add('I', tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = add('W', tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = add('E', tag, message, t)

    private fun add(level: Char, tag: String, message: String, t: Throwable?) {
        when (level) {
            'E' -> Log.e(tag, message, t)
            'W' -> Log.w(tag, message, t)
            else -> Log.i(tag, message)
        }
        val body = capEntry(if (t != null) "$message\n${Log.getStackTraceString(t)}" else message)
        val entry = LogEntry(System.currentTimeMillis(), level, tag, body)
        mutable.value = LogFormat.cap(mutable.value, entry, MAX_ENTRIES)
        val f = file ?: return
        io.execute { runCatching { appendCapped(f, entry) } }
    }

    /**
     * Records a fatal error on the CALLING thread, file write included. Everything else here
     * hands the append to [io] and returns; a crash handler can't do that, because the process
     * is dead long before that executor is next scheduled — which is precisely why crashes used
     * to be the one event missing from the log.
     */
    fun crash(tag: String, message: String, t: Throwable) {
        Log.e(tag, message, t)
        val now = System.currentTimeMillis()
        val entry = LogEntry(now, 'E', tag, capEntry("$message\n${Log.getStackTraceString(t)}"))
        mutable.value = LogFormat.cap(mutable.value, entry, MAX_ENTRIES)
        file?.let { f -> runCatching { appendCapped(f, entry) } }
        // A counter as well as a log line: the log is a 128 KB ring, so a device crashing in a
        // loop overwrites the evidence of the first one, and the parent only ever sees the tail
        // if they ask for a DIAGNOSE. The tally is what reaches them on the next heartbeat.
        runCatching { CrashCounter.record(now) }
    }

    /**
     * One entry at most [MAX_ENTRY_CHARS]: the head of a stack trace says what broke, and a
     * forty-frame tail of framework calls is what turned single entries into a sizeable fraction
     * of the whole file.
     */
    private fun capEntry(body: String): String =
        if (body.length <= MAX_ENTRY_CHARS) body else body.take(MAX_ENTRY_CHARS) + "\n…"

    /** Whole buffer as text, for copy/share. */
    fun format(): String = LogFormat.format(mutable.value)

    /** The newest [n] lines as display text, oldest first (remote diagnostics report). */
    fun tail(n: Int): List<String> = mutable.value.takeLast(n).map { LogFormat.line(it) }

    fun clear() {
        mutable.value = emptyList()
        val f = file ?: return
        io.execute { runCatching { f.writeText("") } }
    }

    private fun appendCapped(f: File, entry: LogEntry) {
        f.appendText(LogFormat.serialize(entry) + "\n")
        if (f.length() > MAX_FILE_BYTES) {
            // Down to three quarters, not to the cap itself: trimming exactly to the limit puts the
            // very next append over it again, and the file is rewritten on every line after all.
            val kept = LogFormat.trimToBytes(f.readLines(), MAX_FILE_BYTES * 3 / 4, MAX_ENTRIES)
            f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        }
    }

    private fun readTail(f: File): List<LogEntry> {
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(MAX_ENTRIES).mapNotNull { LogFormat.deserialize(it) }
    }
}
