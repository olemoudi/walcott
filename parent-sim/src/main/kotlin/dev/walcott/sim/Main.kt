package dev.walcott.sim

import dev.walcott.sync.RemoteAction

/**
 * The harness as a program you can sit in front of.
 *
 * The scenarios in the test suite are the point, but half of what a harness is worth is being
 * able to drive the thing by hand: pair the emulator, push a rule, watch what comes back, try
 * the case you have not written a test for yet. That is what this is.
 *
 *     ./gradlew :parent-sim:run --args="serve"
 *
 * It stands the relay up, enrols whatever device adb can see, and then reads one command per
 * line from stdin:
 *
 *     policy installs                 arm the install block
 *     policy none                     clear every restriction
 *     limit com.some.app 30           30 minutes a day for one app
 *     bedtime 21:30 07:00             the family's bedtime, every day type
 *     screenfree 14:00 15:00          a screen-free window, every day type
 *     schedule none                   drop bedtime and every screen-free window
 *     bonus 15 [pkg]                  minutes granted out of the blue (default: all apps)
 *     usage com.some.app=600          put screen time on the device's own counters
 *     window com.some.app             open the timed install window on the device
 *     cmd diagnose                    remote command, optionally `cmd uninstall_app com.x`
 *     requests                        what the child is waiting on an answer for, with ids
 *     resolve <requestId> yes 15      answer a child's request, granting 15 minutes
 *     watch                           print child snapshots as they land
 *     dump                            what the child last reported
 *     quit
 *
 * The rules are cumulative: each command edits the family's policy and republishes the whole
 * of it, so setting a limit does not silently drop the bedtime set a moment earlier.
 */
fun main(args: Array<String>) {
    val relay = MockRelay().start()
    val device = ChildDevice()
    // Over `adb reverse` when there is a device: the emulator's own network stack is the part
    // that gives out under a long session, and nothing here needs it.
    val advertised = if (device.isAvailable()) {
        device.reversePort(relay.port)
        relay.loopbackUrl
    } else {
        relay.emulatorUrl
    }
    val parent = ParentSim(relay.localUrl, advertisedRelay = advertised).start()

    println("relay: ${relay.localUrl} (device sees $advertised)")
    println("topic: ${parent.topic}")

    if (args.firstOrNull() == "qr") {
        // Just print the pairing text, for pasting into a seed broadcast by hand.
        println(parent.pairingFor())
        return
    }

    if (!device.isAvailable()) {
        println("no device: start an emulator, or use the pairing text above by hand")
        println(parent.pairingFor())
        return
    }

    println("pairing " + ChildDevice.PACKAGE + "…")
    device.pairFresh(parent.pairingFor())
    val first = runCatching { parent.awaitChild { true } }.getOrNull()
    println(if (first != null) "paired: ${first.deviceId} (${first.displayName})" else "no check-in yet")

    // The family's rules, held here so every command edits them rather than replacing them:
    // a `limit` that silently dropped the bedtime set a moment earlier would make every
    // multi-rule case by hand a trap.
    var policyVersion = 1L
    val restrictions = mutableSetOf<String>()
    val limits = mutableMapOf<String, Int>()
    var bedtime: Pair<Int, Int>? = null
    val screenFree = mutableListOf<Pair<Int, Int>>()
    fun push(): String {
        policyVersion++
        parent.pushPolicy(
            PolicyJson.build(
                version = policyVersion,
                restrictions = restrictions,
                dailyMinutes = limits,
                bedtime = bedtime,
                screenFree = screenFree,
            ),
        )
        return "pushed policy v$policyVersion"
    }

    while (true) {
        val line = readlnOrNull()?.trim() ?: break
        val parts = line.split(" ").filter { it.isNotBlank() }
        when (parts.firstOrNull()) {
            null -> Unit
            "quit", "exit" -> break
            "policy" -> {
                restrictions.clear()
                restrictions += parts.drop(1).filterNot { it == "none" }
                println(push() + " restrictions=$restrictions")
            }
            "limit" -> {
                val pkg = parts.getOrNull(1) ?: continue
                val minutes = parts.getOrNull(2)?.toIntOrNull() ?: 30
                limits[pkg] = minutes
                println(push() + ": $pkg = $minutes min/day")
            }
            "bedtime" -> {
                val start = minuteOfDay(parts.getOrNull(1))
                val end = minuteOfDay(parts.getOrNull(2))
                if (start == null || end == null) { println("bedtime HH:MM HH:MM"); continue }
                bedtime = start to end
                println(push() + ": bedtime ${parts[1]}–${parts[2]}")
            }
            "screenfree" -> {
                val start = minuteOfDay(parts.getOrNull(1))
                val end = minuteOfDay(parts.getOrNull(2))
                if (start == null || end == null) { println("screenfree HH:MM HH:MM"); continue }
                screenFree += start to end
                println(push() + ": screen-free ${parts[1]}–${parts[2]}")
            }
            "schedule" -> {
                bedtime = null
                screenFree.clear()
                println(push() + ": no bedtime, no screen-free windows")
            }
            "bonus" -> {
                val deviceId = parent.children.keys.firstOrNull()
                if (deviceId == null) { println("no child yet"); continue }
                val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 15
                // The all-apps sentinel spelt out: `:core-rules` is not on this module's
                // path, and the wire carries the string either way (see ChildDevice).
                val target = parts.getOrNull(2) ?: "__all_apps__"
                val epochDay = java.time.LocalDate.now().toEpochDay()
                parent.grantBonus(deviceId, target, minutes, epochDay)
                println("granted $minutes min on $target (day $epochDay)")
            }
            "usage" -> {
                val entries = parts.drop(1).mapNotNull { entry ->
                    val pkg = entry.substringBefore('=', "")
                    val seconds = entry.substringAfter('=', "").toLongOrNull()
                    if (pkg.isBlank() || seconds == null) null else pkg to seconds
                }
                if (entries.isEmpty()) { println("usage com.some.app=600 …"); continue }
                device.addUsage(*entries.toTypedArray())
                println("added " + entries.joinToString { "${it.first}=${it.second}s" })
            }
            "window" -> {
                val pkg = parts.getOrNull(1) ?: continue
                device.openInstallWindow(pkg)
                println("install window open for $pkg")
            }
            "requests" -> {
                parent.children.values.forEach { child ->
                    child.requests.forEach {
                        println("${it.requestId} time ${it.categoryId} ${it.minutes}min “${it.reason}”")
                    }
                    child.asks.forEach { println("${it.requestId} ${it.kind} “${it.text}”") }
                }
            }
            "cmd" -> {
                val deviceId = parent.children.keys.firstOrNull()
                if (deviceId == null) { println("no child yet"); continue }
                val action = parts.getOrNull(1) ?: RemoteAction.DIAGNOSE
                val id = parent.sendCommand(deviceId, action, parts.getOrNull(2).orEmpty())
                val ack = runCatching { parent.awaitAck(id) }.getOrNull()
                println("$action -> ${ack?.let { "ok=${it.ok} ${it.detail}" } ?: "no ack"}")
            }
            "resolve" -> {
                val requestId = parts.getOrNull(1) ?: continue
                val approved = parts.getOrNull(2) != "no"
                parent.resolve(requestId, approved, parts.getOrNull(3)?.toIntOrNull() ?: 0)
                println("resolved $requestId approved=$approved")
            }
            "watch" -> {
                val seen = parent.childHistory.size
                println("watching; next snapshot…")
                runCatching { parent.awaitChild { parent.childHistory.size > seen } }
                    .onSuccess { println(describe(it)) }
                    .onFailure { println("nothing arrived") }
            }
            "dump" -> parent.children.values.forEach { println(describe(it)) }
            else -> println("? $line")
        }
    }
    parent.stop()
    relay.stop()
}

/** "21:30" as minutes since midnight; null when it isn't a time at all. */
private fun minuteOfDay(text: String?): Int? {
    val hours = text?.substringBefore(':')?.toIntOrNull() ?: return null
    val minutes = text.substringAfter(':', "").toIntOrNull() ?: return null
    return (hours * 60 + minutes).takeIf { hours in 0..23 && minutes in 0..59 }
}

private fun describe(snapshot: dev.walcott.sync.ChildSnapshot): String = buildString {
    append("${snapshot.deviceId} v${snapshot.version} policy=v${snapshot.appliedPolicyVersion}")
    append(" enforcement=${snapshot.enforcement} usageAccess=${snapshot.usageAccessOn}")
    if (snapshot.usage.isNotEmpty()) append(" usage=${snapshot.usage.map { it.categoryId + ":" + it.seconds }}")
    if (snapshot.ruleEvents.isNotEmpty()) append(" events=${snapshot.ruleEvents.map { it.kind + ":" + it.pkg }}")
    if (snapshot.enforcementGaps.isNotEmpty()) append(" gaps=${snapshot.enforcementGaps}")
    if (snapshot.requests.isNotEmpty()) append(" requests=${snapshot.requests.map { it.requestId.take(8) }}")
    if (snapshot.asks.isNotEmpty()) append(" asks=${snapshot.asks.map { it.kind + ":" + it.text }}")
    if (snapshot.unauthorized.isNotEmpty()) append(" quarantined=${snapshot.unauthorized.map { it.pkg }}")
    snapshot.lastCommand?.let { append(" lastAck=${it.detail}") }
}
