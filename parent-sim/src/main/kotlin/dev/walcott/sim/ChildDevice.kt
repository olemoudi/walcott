package dev.walcott.sim

import java.util.concurrent.TimeUnit

/**
 * The child, as this harness can reach it: one Android device, driven over adb.
 *
 * Everything here goes through interfaces the product already has — the debug seed receiver,
 * the package manager, the device policy dumps. Nothing pokes at the app's internals, because a
 * scenario that reached inside would stop proving that the real paths work, which is the only
 * reason to involve a device at all.
 */
class ChildDevice(
    private val serial: String? = null,
    private val adb: String = System.getenv("ADB") ?: defaultAdb(),
) {

    /** Whether a device is attached at all — scenarios skip themselves rather than fail. */
    fun isAvailable(): Boolean = runCatching {
        run("shell", "getprop", "sys.boot_completed").trim() == "1"
    }.getOrDefault(false)

    fun isWalcottInstalled(): Boolean =
        runCatching { run("shell", "pm", "list", "packages", PACKAGE).contains(PACKAGE) }.getOrDefault(false)

    fun isDeviceOwner(): Boolean = runCatching {
        run("shell", "dumpsys", "device_policy").contains("Device Owner:")
    }.getOrDefault(false)

    /**
     * Whether the device has an IP at all.
     *
     * An emulator can lose its virtual network interface and keep answering adb perfectly, at
     * which point every scenario fails on a timeout and reads like a broken channel in the
     * product. Checked up front so that failure names itself.
     */
    fun hasNetwork(): Boolean = runCatching {
        run("shell", "ip", "-4", "addr").lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("inet ") && !trimmed.startsWith("inet 127.")
        }
    }.getOrDefault(false)

    /**
     * Brings the emulator's network back, and answers whether it worked.
     *
     * Under a long run the emulated Wi-Fi interface disappears from the kernel entirely — adb
     * keeps working, so the device looks perfectly healthy while every socket fails with
     * ENETUNREACH. Toggling Wi-Fi recreates it. Repairing beats skipping: a suite that quietly
     * skipped itself is the worst outcome available, because it reports success.
     */
    fun repairNetwork(timeoutMs: Long = 30_000): Boolean {
        if (hasNetwork()) return true
        runCatching { run("shell", "svc", "wifi", "disable") }
        Thread.sleep(2_000)
        runCatching { run("shell", "svc", "wifi", "enable") }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasNetwork()) return true
            Thread.sleep(1_000)
        }
        return false
    }

    // --- Driving the app ---

    /**
     * Pairs this device into [pairingText]'s family through the real pairing path — the same
     * `pairAsChild` a scanned QR calls, relay address and all.
     */
    fun pair(pairingText: String) {
        seed("--es", "mode", "pair", "--es", "pair_with", pairingText)
    }

    /**
     * Wipes and pairs in ONE broadcast, so the wipe cannot land after the pairing.
     *
     * Two broadcasts would be two independent `goAsync` coroutines on the device, and losing
     * that race leaves a device that reports itself paired and never publishes — a silence that
     * looks exactly like a broken channel.
     */
    fun pairFresh(pairingText: String) {
        seed("--es", "mode", "pair", "--es", "pair_with", pairingText, "--ez", "fresh", "true")
    }

    /** Forgets the family, as a fresh install would start. */
    fun reset() = seed("--es", "mode", "reset")

    /** Replaces the stored policy locally, without going through the parent. */
    fun seedPolicy(policyJson: String) {
        seed("--es", "policy_b64", base64(policyJson))
    }

    /** Opens the single-app install window a parent-pushed install would open. */
    fun openInstallWindow(pkg: String) = seed("--es", "open_install_window", pkg)

    /**
     * Makes the OS refuse to uninstall [pkg], reproducing the stuck removal the quarantine
     * ledger exists for. Without it the emulator removes an app within a second and the retry,
     * the reporting and the parent's "let it stay" are unreachable.
     */
    fun blockUninstall(pkg: String) = seed("--es", "block_uninstall", pkg)

    fun unblockUninstall(pkg: String) = seed("--es", "unblock_uninstall", pkg)

    /** Runs the install guard's reconciliation now. */
    fun reconcileInstalls() = seed("--es", "reconcile_installs", "now")

    /** The child asks its parents for something, through the path its home screen uses. */
    fun ask(kind: String, text: String) = seed("--es", "ask", "$kind:$text")

    /** The child asks for more time on [target] ("__all_apps__" or a package). */
    fun requestExtraTime(target: String, minutes: Int, reason: String = "please") =
        seed("--es", "request_time", "$target:$minutes:$reason")

    /** Seconds onto this device's own screen-time counters, as the usage sampler would. */
    fun addUsage(vararg entries: Pair<String, Long>) =
        seed("--es", "add_usage", entries.joinToString(",") { "${it.first}=${it.second}" })

    /** Satisfies the emergency release's start gates without waiting a day of real time. */
    fun panicReady() = seed("--ez", "panic_ready", "true")

    /** The child requests the 24-hour emergency release, through its own gated call. */
    fun startPanic() = seed("--ez", "start_panic", "true")

    /** The child withdraws its own request. */
    fun cancelPanic() = seed("--ez", "cancel_panic", "true")

    /** Clears a seeded request and any standing refusal. */
    fun clearPanic() = seed("--ez", "panic_clear", "true")

    /** Runs the ~30-minute check-in now, so a scenario needn't wait half an hour for one. */
    fun heartbeat() = seed("--es", "heartbeat", "now")

    /**
     * Wakes the screen and keeps it awake.
     *
     * Not cosmetic, and not optional: the enforcement loop deliberately PARKS while the screen is
     * off (it suspends nothing new and burns no wakeups — see EnforcementService), so an emulator
     * that has dozed off during a long run evaluates no rules at all. Every scenario about a
     * schedule or a budget then waits out its timeout for a suspension that was never going to be
     * attempted, and reads exactly like a product that stopped enforcing.
     */
    fun keepAwake(timeoutMs: Long = 15_000) {
        // A long screen timeout AND `stay on while plugged in`, neither of which is enough on its
        // own: `svc power stayon` reports `mStayOn=false` on an emulator (nothing is plugged into a
        // virtual phone), and even a 24-hour timeout does not stop this AVD dozing off within a
        // minute. Hence [nudgeAwake], called from inside every wait rather than once at the start.
        run("shell", "settings", "put", "system", "screen_off_timeout", "86400000")
        run("shell", "svc", "power", "stayon", "true")
        nudgeAwake()
        // Waking is asynchronous, so a probe on the next line can still read Asleep.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !screenAwake()) {
            Thread.sleep(500)
            nudgeAwake()
        }
    }

    /**
     * One wake keypress: cheap, idempotent, and safe to repeat (unlike POWER, which toggles).
     *
     * Called from the scenario wait loops. The enforcement loop parks while the screen is off, so a
     * scenario that waits a minute on a dozing emulator is waiting on a phone that is not deciding
     * anything — and every assertion about a suspension then fails for a reason that has nothing to
     * do with the product.
     */
    fun nudgeAwake() {
        run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    }

    fun screenAwake(): Boolean = run("shell", "dumpsys", "power").contains("mWakefulness=Awake")

    /**
     * Whether this user's credential-encrypted storage is mounted — "is the phone unlocked", as
     * everything else here experiences it.
     *
     * A locked device is not a slow device, it is a device where the app does not exist: its data
     * directory is not mounted, so the process cannot start, the seed receiver never runs and
     * `am start` answers that the activity does not exist. Every scenario then fails identically,
     * at the pairing, with "the device never checked in" — which reads as a product that has
     * stopped talking to its family, and is worth a couple of full suite runs to work out. It
     * happened here.
     *
     * Asked of the ACTIVITY MANAGER rather than of the keyguard: a swipe-only lock screen shows
     * while the user is perfectly unlocked, so `mDreamingLockscreen` would skip suites that would
     * have run. This is the state that actually decides whether there is an app to talk to.
     */
    fun userUnlocked(): Boolean = runCatching {
        run("shell", "dumpsys", "activity").lineSequence()
            .dropWhile { !it.contains("mStartedUsers") }
            .take(4)
            .any { it.contains("RUNNING_UNLOCKED") }
    }.getOrDefault(false)

    /** Pushes a swipe-only keyguard out of the way. A secure lock needs a person and their PIN. */
    fun dismissSwipeKeyguard() {
        run("shell", "wm", "dismiss-keyguard")
    }

    /** Leaves the ringer where a pocket would (see AudioGuard). */
    fun lowerRinger() = seed("--es", "lower_ringer", "now")

    /** The watchdog's ringer pass on demand — the one that catches a phone silenced offline. */
    fun assertRinger() = seed("--es", "assert_ringer", "now")

    /**
     * Publishes this device's snapshot now. The heartbeat publish is throttled by design, so
     * after changing something on the device this is the only way to make it say so without
     * waiting the throttle out.
     */
    fun publish() = seed("--es", "publish", "now")

    fun launch() {
        run("shell", "am", "start", "-n", "$PACKAGE/.MainActivity")
    }

    fun home() {
        run("shell", "input", "keyevent", "KEYCODE_HOME")
    }

    /**
     * One raw seed broadcast; every helper above is a shape of this.
     *
     * Arguments are quoted for the DEVICE's shell, not just this one. `adb shell` joins whatever
     * it is given into a single command string and the phone re-parses it, so an unquoted value
     * with a space in it silently arrives cut at the space — "homework is done" becomes
     * "homework", and the test that notices reads like a product bug about lost text.
     */
    fun seed(vararg args: String) {
        run("shell", "am", "broadcast", "-n", "$PACKAGE/.debug.PolicySeedReceiver", *args.map(::shellQuote).toTypedArray())
    }

    /** Single-quoted for the device shell, with embedded quotes closed and re-opened. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // --- Observing it ---

    fun installedPackages(): Set<String> =
        run("shell", "pm", "list", "packages")
            .lineSequence()
            .mapNotNull { it.trim().removePrefix("package:").takeIf(String::isNotBlank) }
            .toSet()

    fun isInstalled(pkg: String): Boolean = pkg in installedPackages()

    /**
     * Whether the OS reports [pkg] suspended — the claim the parent's UI makes on its behalf.
     * Read from the package dump rather than from the app, so it is the system's answer and not
     * the app's opinion of what it asked for.
     */
    fun isSuspended(pkg: String): Boolean =
        run("shell", "dumpsys", "package", pkg).contains("suspended=true")

    fun userRestrictions(): String = run("shell", "dumpsys", "user")

    /** Whether the OS has this user restriction in force right now (see DeviceRestrictions). */
    fun hasRestriction(key: String): Boolean =
        userRestrictions().substringAfter("Device policy restrictions:", "")
            .substringBefore("Effective restrictions:")
            .contains(key)

    // --- The notification log (see NotificationLog) ---

    /**
     * Grants usage access from the shell, the way a person grants it in Settings.
     *
     * Not a convenience: without it the child fails CLOSED on any family that has a budget (see
     * `RuleEngine.requiresUsageCounting`), so budgets and the rule events that come with them stop
     * being observable at all — and the suite reads as a product that no longer reports what its
     * rules did. It is an AppOp, so no Device Owner can grant it and nothing in the app can
     * either; a fresh or re-imaged AVD simply does not have it, and it cost an hour on 2026-08-18.
     */
    fun ensureUsageAccess() {
        run("shell", "appops", "set", PACKAGE, "GET_USAGE_STATS", "allow")
    }

    /** Whether this device can count screen time at all (see [ensureUsageAccess]). */
    fun usageAccessGranted(): Boolean =
        run("shell", "appops", "get", PACKAGE, "GET_USAGE_STATS").contains("allow")

    /**
     * Grants notification access from the shell, the way a person grants it in Settings.
     *
     * There is no Device Owner path to this — a notification listener is enabled by a human, full
     * stop — so a scenario that could not do this from adb could only ever test the refusal.
     */
    fun allowNotificationListener() {
        run("shell", "cmd", "notification", "allow_listener", NOTIFICATION_LISTENER)
    }

    fun disallowNotificationListener() {
        run("shell", "cmd", "notification", "disallow_listener", NOTIFICATION_LISTENER)
    }

    /**
     * The OS's own answer to whether the listener is enabled.
     *
     * Needed because `allow_listener`/`disallow_listener` return as soon as the command is
     * dispatched, not when the setting has been written — so a scenario that acts on the next line
     * can read the state it was trying to change, and then reports the product as wrong.
     */
    fun notificationListenerAllowed(): Boolean =
        run("shell", "settings", "get", "secure", "enabled_notification_listeners")
            .contains(NOTIFICATION_LISTENER)

    /**
     * Posts a notification from the shell package, so the listener has something real to record.
     *
     * It arrives attributed to `com.android.shell` rather than to a messaging app, which is
     * exactly right for what is under test: the log stores whatever posted it, and the scenario
     * asserts on the round trip, not on who sent it.
     */
    fun postNotification(tag: String, title: String, text: String) {
        run("shell", "cmd", "notification", "post", "-t", shellQuote(title), shellQuote(tag), shellQuote(text))
    }

    fun installBlocked(): Boolean =
        userRestrictions().substringAfter("Device policy restrictions:", "")
            .substringBefore("Effective restrictions:")
            .contains("no_install_apps")

    fun install(apkPath: String): String = run("install", "-r", apkPath)

    fun uninstall(pkg: String): String = run("uninstall", pkg)

    /**
     * Gets [pkg] off the device and does not return until it is gone.
     *
     * Every seed broadcast is asynchronous — `am broadcast` returns when the receiver was
     * *dispatched*, not when it finished — so lifting an uninstall block and immediately
     * uninstalling races, and losing that race leaves the next scenario starting on a device
     * with a package it was promised would not be there. Retrying until the state is real is
     * the only honest way to clean up over adb.
     */
    fun ensureRemoved(pkg: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isInstalled(pkg)) return
            unblockUninstall(pkg)
            uninstall(pkg)
            Thread.sleep(500)
        }
        check(!isInstalled(pkg)) { "could not remove $pkg from the device" }
    }

    /**
     * Makes the host's [port] answer on the device's own loopback, over the adb transport.
     *
     * The device then reaches the relay without involving its network stack at all — which is
     * the difference between a suite that survives the emulator losing its Wi-Fi interface and
     * one that spends its time diagnosing that.
     */
    fun reversePort(port: Int) {
        reversePort(devicePort = port, hostPort = port)
    }

    /**
     * The asymmetric form: the device keeps knocking on [devicePort] while the host answers on
     * [hostPort].
     *
     * What it buys is a relay that can be taken away and brought back WITHOUT the child being told
     * anything — the address it holds never changes, only what is behind it. Re-binding the same
     * host port instead does not work: a listener whose connections have just been cut cannot be
     * re-created for as long as those linger, which is exactly the moment a scenario about an
     * outage needs it (see OutageScenarioTest).
     */
    fun reversePort(devicePort: Int, hostPort: Int) {
        run("reverse", "tcp:$devicePort", "tcp:$hostPort")
    }

    fun clearReverse(port: Int) {
        runCatching { run("reverse", "--remove", "tcp:$port") }
    }

    /** Puts a fix under the device, so "locate now" has something to find. */
    fun setLocation(latitude: Double, longitude: Double) {
        // The console takes longitude first, which is the opposite of how everyone says it.
        run("emu", "geo", "fix", longitude.toString(), latitude.toString())
    }

    fun clearLogcat() {
        run("logcat", "-c")
    }

    fun logcat(): String = run("logcat", "-d")

    /** Lines the app itself wrote, which is where its own reasoning is visible. */
    fun walcottLog(): List<String> =
        logcat().lineSequence().filter { "Walcott" in it }.toList()

    // --- Plumbing ---

    private fun base64(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray())

    fun run(vararg args: String): String {
        val command = buildList {
            add(adb)
            if (serial != null) { add("-s"); add(serial) }
            addAll(args)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(ADB_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("adb timed out: ${command.joinToString(" ")}")
        }
        return output
    }

    companion object {
        const val PACKAGE = "dev.walcott"
        private const val ADB_TIMEOUT_SEC = 120L

        /** Flattened component of the notification listener declared in the app's manifest. */
        private const val NOTIFICATION_LISTENER =
            "$PACKAGE/$PACKAGE.notifications.WalcottNotificationListener"

        /** The SDK's adb if it isn't on PATH, which it usually isn't on a dev box. */
        private fun defaultAdb(): String {
            val home = System.getProperty("user.home")
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "$home/Android/Sdk"
            val candidate = java.io.File("$sdk/platform-tools/adb")
            return if (candidate.canExecute()) candidate.absolutePath else "adb"
        }
    }
}
