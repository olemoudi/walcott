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
        run("reverse", "tcp:$port", "tcp:$port")
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

        /** The SDK's adb if it isn't on PATH, which it usually isn't on a dev box. */
        private fun defaultAdb(): String {
            val home = System.getProperty("user.home")
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "$home/Android/Sdk"
            val candidate = java.io.File("$sdk/platform-tools/adb")
            return if (candidate.canExecute()) candidate.absolutePath else "adb"
        }
    }
}
