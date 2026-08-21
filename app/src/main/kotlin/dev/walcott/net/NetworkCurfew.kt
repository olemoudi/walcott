package dev.walcott.net

import dev.walcott.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The apps resolving nothing right now, because the phone is supposed to be shut
 * (see [dev.walcott.rules.Curfew]).
 *
 * Derived state with a single writer — the enforcement loop, which is the only thing that knows
 * what hour it is and what the rules say about it — and a single reader, the DNS tunnel, which
 * checks it per query. Both live in this process, so this is a field and not a store.
 *
 * In memory ON PURPOSE, and it is the safety property of the whole feature: nothing here
 * survives the process. A phone that reboots, crashes or is force-stopped comes back resolving
 * everything, and the loop puts the curfew back within a tick if the window is still running.
 * The failure that is ruled out by construction is the other one — a browser that stopped
 * working at some point last week, with no rule on any screen that would explain it.
 */
object NetworkCurfew {

    private val _packages = MutableStateFlow<Set<String>>(emptySet())

    /** Followed by the filter's own on/off decision: a curfew is a reason to have a tunnel. */
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    /** Replaces the whole set; logs only real changes, since the loop asserts this every tick. */
    fun set(value: Set<String>) {
        if (value == _packages.value) return
        DebugLog.i(
            TAG,
            if (value.isEmpty()) "curfew lifted" else "curfew: ${value.sorted().joinToString()} resolve nothing",
        )
        _packages.value = value
    }
}

private const val TAG = "WalcottCurfew"
