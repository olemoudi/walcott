package dev.walcott.net

import android.content.Context
import dev.walcott.debug.DebugLog
import dev.walcott.rules.BlocklistSource
import dev.walcott.rules.Blocklists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okio.BufferedSource
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The child's copy of the public blocklists behind [Blocklists].
 *
 * One file per list under `files/blocklists/`, holding the parsed domains one per line, plus a
 * small `state.json` describing what is in them. The domains are never held here as strings:
 * they are streamed from the network into a file and from that file into a
 * [dev.walcott.rules.DomainMatcher.Builder], because the lists run to half a million entries
 * each and a `Set<String>` of one of them is 50 MB of heap for no reason. What this exposes
 * instead is [state] — counts and timestamps, a few hundred bytes — which is what the UI, the
 * snapshot the parent reads, and the filter's "something changed, recompile" signal all need.
 *
 * Kept as plain text on disk rather than as the compiled hashes on purpose: a cache you can
 * `grep` is the difference between answering "why is this domain blocked on my kid's phone?" in
 * one command and never being able to answer it at all.
 *
 * Everything here is written to survive a family that never looks at it:
 *
 *  - **Atomic.** A list is written to `.tmp` and renamed, so a download killed halfway leaves
 *    the previous copy in place rather than half a filter.
 *  - **Sanity-checked.** A source that answers with an error page, a rate-limit notice or a
 *    redirect parses to a handful of junk domains — GitHub does answer `429 Too Many Requests`
 *    with 199 bytes of prose, and that must not be allowed to replace 494 000 domains with none.
 *    A result far smaller than the list is known to be is refused and the previous copy kept.
 *  - **Conditional.** Each source's ETag is remembered, so a refresh normally costs a 304 and no
 *    bytes at all. Without it the adult list alone would re-download ~10 MB of the child's data
 *    on every pass to learn nothing.
 *  - **Honest when it fails.** A list the parent switched on but this device has never managed to
 *    download is reported as pending, all the way up to the parent's screen. The alternative is a
 *    family believing they filter 494 000 domains while they filter the 55 in the APK.
 */
class BlocklistStore private constructor(private val context: Context) {

    @Serializable
    data class ListState(
        /** Domains cached on this device for this list (0 = never downloaded successfully). */
        val domains: Int = 0,
        /** When the cache was last written or confirmed unchanged, wall-clock ms (0 = never). */
        val fetchedAtMs: Long = 0,
        /** True when a source had more to give than [BlocklistSource.MAX_DOMAINS] allowed. */
        val trimmed: Boolean = false,
        /** Per-source ETag from the last successful download, for the conditional request. */
        val etags: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class State(val lists: Map<String, ListState> = emptyMap()) {

        /** Domains this device has downloaded for [ids], ignoring lists it has never fetched. */
        fun domainsFor(ids: Collection<String>): Int = ids.sumOf { lists[it]?.domains ?: 0 }

        /**
         * Of [ids], the lists with a public source this device has not got. Ordered like
         * [Blocklists.ALL] so the parent's screen names them in the order they were offered.
         */
        fun pending(ids: Collection<String>): List<String> =
            Blocklists.withSources(ids).filter { (lists[it]?.domains ?: 0) == 0 }
    }

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<State> = _state.asStateFlow()

    /** One refresh at a time: two would fight over the same files and download everything twice. */
    private val refreshLock = Mutex()

    private val client = Http.client.newBuilder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Streams every cached domain for [ids] through [sink], in canonical form.
     *
     * The caller (the DNS filter) feeds them straight into a matcher builder, so at no point does
     * a million-domain filter exist as a million strings. IO, so never on the main thread.
     */
    suspend fun readInto(ids: Collection<String>, sink: (String) -> Unit) = withContext(Dispatchers.IO) {
        for (id in Blocklists.known(ids)) {
            val file = fileFor(id)
            if (!file.exists()) continue
            runCatching { file.forEachLine { if (it.isNotEmpty()) sink(it) } }
                .onFailure { DebugLog.w(TAG, "could not read the cached $id list", it) }
        }
    }

    /**
     * Downloads whatever [ids] needs and prunes what they no longer do.
     *
     * A list refreshed less than [intervalHours] ago is left alone, which is the family's own
     * setting (see `PolicySettings.blocklistRefreshHours`). A list the family has just switched on
     * has never been fetched, so it downloads immediately whatever the interval says — which is
     * what makes "turn it on and it works" true without a second, forcing code path.
     *
     * Returns true when every list the policy asks for is now cached.
     */
    suspend fun refresh(ids: Collection<String>, intervalHours: Int): Boolean = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val wanted = Blocklists.withSources(ids)
            prune(keep = wanted.toSet())
            // A tenth under the interval on purpose: WorkManager fires a periodic job when it
            // suits the system, and a run arriving five minutes early must not decide the list is
            // still fresh — that turns a weekly refresh into a fortnightly one.
            val staleAfterMs = TimeUnit.HOURS.toMillis(intervalHours.toLong().coerceAtLeast(1)) * 9 / 10
            var complete = true
            for (id in wanted) {
                val current = _state.value.lists[id] ?: ListState()
                val fresh = current.domains > 0 &&
                    System.currentTimeMillis() - current.fetchedAtMs < staleAfterMs
                if (fresh) continue
                if (!refreshOne(id, current) && current.domains == 0) complete = false
            }
            complete
        }
    }

    /** Downloads and stores one list. Returns false when the cache was left as it was. */
    private fun refreshOne(id: String, current: ListState): Boolean {
        val sources = Blocklists.sources(id)
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val tmp = File(dir, "$id.tmp")
        val etags = mutableMapOf<String, String>()
        var domains = 0
        var trimmed = false
        var unchanged = 0

        val wrote = runCatching {
            tmp.bufferedWriter().use { writer ->
                for (url in sources) {
                    when (val result = fetchInto(url, current.etags[url], writer, domains)) {
                        is Fetch.Failed -> {
                            // One source of several failing must not publish a list missing a
                            // third of itself: keep what we have and try the next pass.
                            DebugLog.w(TAG, "$id: $url did not download (${result.reason}); keeping the cached copy")
                            return@runCatching false
                        }
                        is Fetch.Unchanged -> {
                            unchanged++
                            current.etags[url]?.let { etags[url] = it }
                        }
                        is Fetch.Stored -> {
                            domains += result.domains
                            trimmed = trimmed || result.trimmed
                            result.etag?.let { etags[url] = it }
                        }
                    }
                }
            }
            true
        }.getOrElse {
            DebugLog.w(TAG, "$id: could not be written", it)
            false
        }

        if (!wrote) {
            runCatching { tmp.delete() }
            return false
        }

        // Every source answered 304: the cache is already what they would have sent. Confirm it
        // and touch nothing else, so the next pass waits out the interval again.
        if (unchanged == sources.size && current.domains > 0) {
            runCatching { tmp.delete() }
            update(id) { it.copy(fetchedAtMs = System.currentTimeMillis(), etags = etags) }
            // Logged because it is the good outcome and would otherwise be indistinguishable from
            // nothing having run at all: the sources answered "you already have it", for no bytes.
            DebugLog.i(TAG, "$id: unchanged (${current.domains} domains, nothing downloaded)")
            return true
        }
        // A mix of 304s and fresh bodies would leave a file missing the unchanged sources, so
        // re-ask for everything unconditionally rather than publish a thinner list.
        if (unchanged > 0) {
            runCatching { tmp.delete() }
            DebugLog.i(TAG, "$id: some sources were unchanged and some were not; refetching in full")
            return refreshOne(id, current.copy(etags = emptyMap()))
        }
        if (looksWrong(id, domains)) {
            DebugLog.w(
                TAG,
                "$id: the sources parsed to only $domains domains; treating that as a bad " +
                    "answer and keeping the cached copy",
            )
            runCatching { tmp.delete() }
            return false
        }

        if (!runCatching { tmp.renameTo(File(dir, "$id.txt")) }.getOrDefault(false)) {
            DebugLog.w(TAG, "$id: could not replace the cached list")
            runCatching { tmp.delete() }
            return false
        }
        update(id) {
            ListState(
                domains = domains,
                fetchedAtMs = System.currentTimeMillis(),
                trimmed = trimmed,
                etags = etags,
            )
        }
        DebugLog.i(TAG, "$id: $domains domains cached${if (trimmed) " (source trimmed)" else ""}")
        return true
    }

    private sealed interface Fetch {
        data class Stored(val domains: Int, val trimmed: Boolean, val etag: String?) : Fetch
        data object Unchanged : Fetch
        data class Failed(val reason: String) : Fetch
    }

    /**
     * Downloads [url] and appends its domains to [writer], one per line.
     *
     * Streamed line by line: the largest source is ~10 MB of text, and holding it whole would
     * spike a phone's heap for the sake of a loop we can run as it arrives. [alreadyStored] is
     * what earlier sources of the same list have contributed, so the ceiling applies to the list
     * rather than to each of its sources.
     */
    private fun fetchInto(url: String, etag: String?, writer: BufferedWriter, alreadyStored: Int): Fetch {
        val request = Request.Builder().url(url).apply {
            if (etag != null) header("If-None-Match", etag)
        }.build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_NOT_MODIFIED -> Fetch.Unchanged
                    !response.isSuccessful -> Fetch.Failed("HTTP ${response.code}")
                    else -> {
                        val body = response.body ?: return@use Fetch.Failed("no body")
                        store(body.source(), writer, alreadyStored)
                            .let { (domains, trimmed) ->
                                Fetch.Stored(domains, trimmed, response.header("ETag"))
                            }
                    }
                }
            }
        }.getOrElse { Fetch.Failed(it.javaClass.simpleName) }
    }

    /** Copies the domains out of [source] into [writer]; returns how many, and whether it capped. */
    private fun store(source: BufferedSource, writer: BufferedWriter, alreadyStored: Int): Pair<Int, Boolean> {
        var domains = 0
        var bytes = 0L
        while (true) {
            val line = source.readUtf8Line() ?: return domains to false
            bytes += line.length + 1
            // Bounded read: a URL that quietly starts serving a full threat-intelligence feed
            // must stop here rather than in the middle of the child's storage.
            if (bytes > MAX_BODY_BYTES || alreadyStored + domains >= BlocklistSource.MAX_DOMAINS) {
                return domains to true
            }
            val domain = BlocklistSource.domainOf(line) ?: continue
            writer.write(domain)
            writer.write("\n")
            domains++
        }
    }

    /**
     * True when a refresh produced far less than the list is known to carry — the shape of an
     * error page, a rate-limit notice or a source that has moved, not of a real list.
     *
     * Judged against [Blocklists.Entry.approxSourceDomains] with a lot of slack (a quarter), so a
     * list that has genuinely shrunk between releases still lands; and not applied at all to a
     * source we have no measurement for.
     */
    private fun looksWrong(id: String, domains: Int): Boolean {
        val expected = Blocklists.entry(id)?.approxSourceDomains ?: 0
        if (expected == 0) return false
        return domains < maxOf(MIN_DOMAINS, expected / 4)
    }

    /**
     * Forgets every cached list, for a device that has just been handed back (see
     * [dev.walcott.enforcement.PanicRelease]). Half a million domains in `files/` is not personal
     * data, but the promise a release makes is that nothing is left to suggest the phone was ever
     * enrolled — and a folder of blocklists is exactly such a trace.
     */
    fun clear() {
        runCatching { File(context.filesDir, DIR).deleteRecursively() }
            .onFailure { DebugLog.w(TAG, "could not delete the cached lists", it) }
        _state.value = State()
    }

    /** Forgets the lists the family has switched off, on disk and in [state]. */
    private fun prune(keep: Set<String>) {
        val gone = _state.value.lists.keys - keep
        if (gone.isEmpty()) return
        for (id in gone) runCatching { fileFor(id).delete() }
        _state.value = State(_state.value.lists - gone).also { writeState(it) }
        DebugLog.i(TAG, "dropped the cached lists no longer switched on: ${gone.joinToString()}")
    }

    private fun update(id: String, block: (ListState) -> ListState) {
        val lists = _state.value.lists
        val next = State(lists + (id to block(lists[id] ?: ListState())))
        _state.value = next
        writeState(next)
    }

    private fun fileFor(id: String) = File(File(context.filesDir, DIR), "$id.txt")

    private fun stateFile() = File(File(context.filesDir, DIR).apply { mkdirs() }, "state.json")

    private fun readState(): State = runCatching {
        val file = stateFile()
        if (!file.exists()) State() else json.decodeFromString(State.serializer(), file.readText())
    }.getOrElse {
        DebugLog.w(TAG, "blocklist state unreadable; starting from nothing", it)
        State()
    }

    private fun writeState(state: State) {
        runCatching { stateFile().writeText(json.encodeToString(State.serializer(), state)) }
            .onFailure { DebugLog.w(TAG, "could not store the blocklist state", it) }
    }

    companion object {
        private const val TAG = "Blocklists"
        private const val DIR = "blocklists"
        private const val HTTP_NOT_MODIFIED = 304

        /** Ceiling on one source's download; see [store]. */
        private const val MAX_BODY_BYTES = 48L * 1024 * 1024

        /** Below this, an answer is not a list at all (see [looksWrong]). */
        private const val MIN_DOMAINS = 100

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Volatile private var instance: BlocklistStore? = null

        /**
         * The process-wide store (the DNS filter, the refresh worker and the sync publisher share
         * one). Constructing it reads a small state file, so call it off the main thread.
         */
        fun get(context: Context): BlocklistStore =
            instance ?: synchronized(this) {
                instance ?: BlocklistStore(context.applicationContext).also { instance = it }
            }
    }
}
