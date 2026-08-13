# parent-sim — a parent you can run, so the child can be tested

A parent device does nothing a JVM cannot. It holds the family's keys, signs snapshots of the
rules, and reads what the children publish back; the screens and notifications are presentation
over that. The **child** is the half that has to be Android, because what is being tested there
*is* Android — suspending packages, blocking installs, counting screen time, surviving a reboot.

So this module is the other side of every conversation a child can have, as a program:

| Piece | What it is |
| --- | --- |
| `MockRelay` | An ntfy-compatible relay on this machine (POST publish, `/ws` subscribe, `since=` replay). Also able to **drop** and **replay** messages, which is how the sync layer's failure handling gets exercised at all. |
| `ParentSim` | A real protocol parent: mints the family, emits the pairing QR, signs and publishes `ParentSnapshot`s, decodes everything the child sends. |
| `PolicyJson` | The rules as the string that actually travels, built by hand so the module never depends on `:app`. |
| `ChildDevice` | The device over adb — pair, seed, install, observe. |
| `Main` | The same thing with a prompt, for driving a device by hand. |

Nothing in `:app` depends on this module and nothing here depends on `:app`. If that ever stops
being true, the parent it simulates has stopped being pure logic and the premise is wrong.

## Running it

**Hermetic half — runs in CI, needs nothing:**

```sh
./gradlew :parent-sim:test
```

Relay and parent talking to each other on this machine, plus a child made of nothing but
`SyncProtocol`. This is what proves the harness before the harness is used to prove anything
else: if the sim signed wrongly or read the wire wrongly, a failure on a device would be blamed
on the product.

**Device half — needs an emulator:**

```sh
./gradlew :parent-sim:e2eTest
```

Excluded from `check` and from `./gradlew test` on purpose: CI has no device, and a suite that
is skipped by default is worse than one you have to ask for. Every scenario checks its
preconditions and says so out loud; a run where *nothing* executed fails the task rather than
reporting a green build that tested nothing.

**By hand:**

```sh
./gradlew :parent-sim:run --args="serve"     # pairs the attached device, then reads commands
./gradlew :parent-sim:run --args="qr"        # just print a pairing payload
```

## Setting a device up

The scenarios need the debug build, Device Owner, and a network.

```sh
# 1. Build and sign with the release key (the AVD's Device Owner is bound to it)
./gradlew assembleDebug
apksigner sign --ks walcott-release.jks --ks-pass pass:walcott --key-pass pass:walcott \
  --ks-key-alias walcott --out walcott-debug.apk app/build/outputs/apk/debug/app-debug.apk

# 2. Install (the install block must be off, or adb itself is refused — see below)
adb install -r walcott-debug.apk
adb shell dpm set-active-admin --user 0 dev.walcott/dev.walcott.WalcottAdminReceiver
```

### Provisioning Device Owner from scratch

`dpm set-device-owner` fails with *"there are already some accounts on the device"* on a
`google_apis` image even when `dumpsys account` reports zero accounts: it is the registered
account **authenticators** that count, not actual accounts. Disable the packages that register
them first:

```sh
adb shell pm disable-user --user 0 com.google.android.gms
adb shell pm disable-user --user 0 com.google.android.gsf
adb shell pm disable-user --user 0 com.google.android.gm
adb shell dpm set-device-owner dev.walcott/dev.walcott.WalcottAdminReceiver
```

### The install block locks adb out too

That is the feature working. With `installs` armed, nothing installs — including `adb install`,
including a new build of Walcott itself. Lift it first:

```sh
adb shell am broadcast -n dev.walcott/.debug.PolicySeedReceiver \
  --es policy_b64 "$(echo -n '{"version":999,"deviceRestrictions":[]}' | base64 -w0)"
```

The suite does this in its own setup and teardown, so a failing scenario cannot leave the next
one unable to start.

### The emulator loses its network

Under a long run the emulated Wi-Fi interface disappears from the kernel entirely. adb keeps
working, so the device looks healthy while every socket fails with `ENETUNREACH`. The harness
detects it and repairs it (`svc wifi disable/enable`) before skipping anything; if you are
driving by hand, that is the fix.

### Keep the debug build's versionCode equal to the published one

The child auto-updates from GitHub Releases, silently, as Device Owner. If the debug build on
the emulator has a lower `versionCode` than the latest release, it *will* be replaced mid-session
by the release build — which contains no debug receiver, so every hook disappears and the
scenarios fail in a way that has nothing to do with them.

## The debug hooks these lean on

All in `PolicySeedReceiver` (debug builds only; absent from release):

| Extra | What it does |
| --- | --- |
| `--es mode pair --es pair_with <walcott1:…> [--ez fresh true]` | Pair through the real path. `fresh` wipes identity and state first, in the same coroutine — as two broadcasts the wipe can land *after* the pairing. |
| `--es mode reset` | Forget everything, as a fresh install would start. |
| `--es policy_b64 <b64>` | Replace the stored policy locally. |
| `--es publish now` | Publish the snapshot now (the heartbeat publish is throttled by design). |
| `--es heartbeat now` | The whole ~30-minute check-in, on demand. |
| `--es ask "kind:text"`, `--es request_time "target:minutes:reason"` | The two things a *child* initiates. |
| `--es open_install_window <pkg>`, `--es reconcile_installs now` | The install guard's two moving parts. |
| `--es block_uninstall <pkg>` / `--es unblock_uninstall <pkg>` | Make the OS genuinely refuse to remove a package, which is the only way to reproduce a stuck removal on an emulator. |

## What it has already caught

The install guard kept a quarantine case open after the case was over, because the pass that
closes cases was skipped whenever the guard was not judging. The consequence was invisible until
two devices were in the room: an app quarantined once could never be installed again — including
by the parent approving it properly, in its own window, at which point it was suspended and
removed on arrival. Fixed in `InstallGuard.retained`; pinned by
`InstallGuardScenarioTest.an app quarantined once can still be approved and installed later`.
