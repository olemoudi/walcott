package dev.walcott.sync

/**
 * How a whole family is doing, in the two numbers a parent picking between families needs:
 * how many of its children want something, and how many of them are in a state worth looking at.
 *
 * Pure, because "1 aviso" on a chooser card is a promise: a family that shows zero must really
 * have nothing wrong with it, and that has to be checkable without a device.
 */
object FamilyHealth {

    /**
     * Children currently in a state the parent should see: protection off or degraded, screen
     * time not being counted, the OS not actually suspending what the rules block, a web filter
     * the rules ask for that isn't running, a clock that disagrees with the server, an app that
     * arrived without approval, an emergency release running, or a device that has gone silent
     * for longer than any benign Doze gap.
     *
     * Counted per CHILD, not per symptom: three problems on one phone are one child to go and
     * look at, and a card that said "3 avisos" for it would read as three children in trouble.
     */
    fun alerts(children: List<ChildSnapshot>, lastSeen: Map<String, Long>, nowMs: Long): Int =
        children.count { child ->
            child.enforcement == EnforcementStatus.NONE ||
                !child.usageAccessOn ||
                child.enforcementGaps.isNotEmpty() ||
                webFilterDown(child) ||
                ClockGuard.isTampered(child.clockSkewMs) ||
                child.unauthorized.isNotEmpty() ||
                child.panic != null ||
                Staleness.tierOf(lastSeen[child.deviceId], nowMs) == Staleness.Tier.SILENT
        }

    /**
     * The rules ask this child for a DNS filter and its tunnel is not up — so the domains the
     * parent blocked are resolving normally. Both halves are required: a family with no web
     * filter at all has no tunnel to miss, and a legacy child reports neither.
     */
    fun webFilterDown(child: ChildSnapshot): Boolean = child.webFilterExpected && !child.webFilterOn

    /**
     * Everything a child has asked for and nobody has answered: extra time, app installs,
     * free-form asks, and complete domain batches. Deliberately one number — on a chooser the
     * question is "is anyone waiting on me here", not what for.
     */
    fun pending(state: SyncState): Int {
        val resolved = state.resolutions.map { it.requestId }.toSet()
        val asks = state.children.sumOf { child ->
            child.requests.count { it.requestId !in resolved } + child.asks.count { it.requestId !in resolved }
        }
        val domains = state.domainInbox.count { it.complete && it.batchId !in state.domainsHandled }
        return asks + domains
    }
}
