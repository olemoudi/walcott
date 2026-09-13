package dev.walcott.net

import dev.walcott.rules.Blocklists

/**
 * Everything the downloaded half of the filter is compiled from, and nothing else — so the filter
 * can tell "the lists changed" from "something was said about the lists".
 *
 * The distinction is megabytes. The matcher used to be rebuilt on every emission of the rules or
 * the store's state, and the store emits on every refresh pass, including the ordinary one where
 * each source answers 304 and nothing at all changed. Each rebuild re-read every enabled list
 * from disk — half a million lines for the adult list alone — to produce the matcher already in
 * hand.
 *
 * A confirmation is recognisable from [BlocklistStore.State] alone: it rewrites `fetchedAtMs` and
 * nothing else, and it can only happen when every source of the list sent an ETag (a 304 needs a
 * conditional request). So for such a list the timestamp is left out of the identity. A list with
 * a source that sends no ETag is downloaded in full every pass and may have changed; its
 * timestamp stays in, and it is recompiled.
 */
internal data class ListsIdentity(
    /** The bundled domains of the enabled lists, which ride in the same matcher. */
    val bundled: Set<String>,
    /** Each enabled list, as the store describes its cached copy (null: never downloaded). */
    val lists: Map<String, BlocklistStore.ListState?>,
) {
    companion object {
        fun of(enabled: Set<String>, bundled: Set<String>, state: BlocklistStore.State) = ListsIdentity(
            bundled = bundled,
            lists = enabled.associateWith { id ->
                state.lists[id]?.let { list ->
                    val confirmable = list.etags.isNotEmpty() && list.etags.size >= Blocklists.sources(id).size
                    if (confirmable) list.copy(fetchedAtMs = 0) else list
                }
            },
        )
    }
}
