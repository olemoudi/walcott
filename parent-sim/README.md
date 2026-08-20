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

### A locked phone is not a slow phone

If the emulator's user is locked (a PIN set, and nobody has typed it since boot), the app's
credential-encrypted data is not mounted: the process cannot start, the seed receiver never runs,
and `am start` reports that the activity does not exist. Every scenario then fails identically at
the pairing with *"the device never checked in"*, which reads exactly like a product that has
stopped talking to its family. It cost two full suite runs to work out.

The suite now checks it and skips, loudly (`DeviceScenario` → `ChildDevice.userUnlocked`, asked of
the activity manager rather than the keyguard, so a swipe-only lock screen does not skip a suite
that would have run). Nothing here can type a PIN, so unlock it first:

```sh
adb shell input keyevent KEYCODE_WAKEUP && adb shell input swipe 540 1800 540 600
adb shell input text 4291 && adb shell input keyevent KEYCODE_ENTER
```

### The emulator loses its network

Under a long run the emulated Wi-Fi interface disappears from the guest kernel entirely. adb
keeps working, so the device looks perfectly healthy while every socket fails with
`ENETUNREACH`, and `-feature -Wifi` does not prevent it (the AVD uses virtio-wifi regardless).

The suite is not exposed to this: the relay is reached over `adb reverse`, on the device's own
loopback, which travels on the adb transport rather than the emulator's network stack. Debug
builds permit cleartext to `127.0.0.1` for exactly that reason. `svc wifi disable/enable`
recreates the interface if you need real connectivity for something else.

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
| `--es add_usage "pkg=SECONDS,…"` | Seconds onto the device's own screen-time counters, through the sampler's own call. An emulator nobody has used has no screen time to report. |
| `--ez panic_ready true`, `--ez start_panic true`, `--ez cancel_panic true`, `--ez panic_clear true` | The emergency release from the child's side, without waiting a day of real time for its gates. |
| `--es block_uninstall <pkg>` / `--es unblock_uninstall <pkg>` | Make the OS genuinely refuse to remove a package, which is the only way to reproduce a stuck removal on an emulator. |

## What the device suite covers

| Area | Scenarios | The thing only two devices can show |
| --- | --- | --- |
| Enrolment | 5 | A real QR rewrites a device's identity and it reports back; a re-pair keeps its id. |
| Rules | 7 | Applied by the OS; a signed snapshot at an already-applied version is refused. |
| Remote commands | 8 | Acknowledged, and applied exactly once across re-emits and relay replays. |
| Requests | 6 | A child asks, a parent answers, and the grant lands exactly once. |
| Install guard | 10 | An app really appears, is really suspended, and the parent's two answers really land. |
| Emergency release | 6 | The child's one door out, and a refusal that has to reach it and stick. |
| Reporting | 6 | Screen time per app, the app list, battery, enforcement backend, a clean bill of health. |
| Bonuses | 5 | Granted once, on the right phone, however often the snapshot repeats. |
| Locate & icons | 6 | A fix and a rendered icon, both answered once and marked as answered. |
| The wall | 3 | A limit set here, time counted there, and the moment it ran out reported back. |
| Health flags | 2 | Screen-time counting taken away and given back; the charger. |
| Update window | 2 | `no_install_apps` really leaving the OS for the hour, and really coming back. |
| Location trail | 2 | Two places, in order, with history on — and only one with it off. |
| Web filter | 2 | A real `VpnService` establish, and its withdrawal. |
| Time warnings | 3 | A warning the platform actually put ON SCREEN, once per thing that is closing. |

Still uncovered, and worth knowing:

- **The domain monitor's chunked delivery and its acks.** Needs the child's tunnel to observe real
  DNS lookups, which needs traffic the `adb reverse` transport does not carry.
- **Key rotation after a parent restores from backup** beyond what `RestoredParentScenarioTest`
  already drives.
- **A web filter that is expected but genuinely DOWN.** This is the one that looked reachable and
  is not. Taking `ACTIVATE_VPN` away with `appops` does not refuse the tunnel on a Device Owner —
  verified on this image, where the app logs `DNS tunnel established` with the op set to `ignore`,
  because a Device Owner's VPN needs no consent. Reproducing it needs a second VPN app to win the
  slot, or an OEM that kills the service. What IS pinned is the healthy state and the withdrawal.

Three things worth knowing about what these assert:

- **A notification that exists is not a notification anyone saw.** `TimeWarningScenarioTest` reads
  the notification manager and checks two things: the platform's own `mIsInterruptive`, AND that
  the record is not filed under the group `silent`. The second is the one that earns its keep —
  `NotificationCompat.setSilent(true)` files a notification under that group with
  GROUP_ALERT_SUMMARY, and with no summary in the group to alert on its behalf it is never allowed
  to surface. Every time warning this app sent was going straight to the shade, unseen, while the
  platform still recorded it as interruptive. Nothing short of looking at the screen could tell.
- Do NOT try to clear the shade between scenarios by snoozing. There is no shell verb that cancels
  another app's notification, and Android goes on suppressing later posts of a snoozed key — so
  clearing it that way silences the warning the next scenario is waiting for, and it looks exactly
  like a product that stopped warning. Mark the time with `ChildDevice.deviceNowMs()` and filter on
  `postedAtMs` instead.

- `appliedPolicyVersion` reports the **snapshot's** counter, not the policy JSON's own `version`.
  Both exist, `PolicyJson` warns about it, and waiting on the wrong one waits for a number that is
  never coming. Use the `ParentSnapshot` that `pushPolicy` hands back.
- `webFilterExpected` and `webFilterOn` do not settle in the same breath: the first flips when the
  rules land, the second a few seconds later when the tunnel is up. Until then `webFilterOn` reads
  false, because `VpnStatus` has been counting "down" since the process started — so a family
  switching the filter on sees a brief, genuine "your filter is not running" on the parent's
  screen. The scenario waits for the state that lasts and notes the transient rather than pinning
  it.

## What it has already caught

The install guard kept a quarantine case open after the case was over, because the pass that
closes cases was skipped whenever the guard was not judging. The consequence was invisible until
two devices were in the room: an app quarantined once could never be installed again — including
by the parent approving it properly, in its own window, at which point it was suspended and
removed on arrival. Fixed in `InstallGuard.retained`; pinned by
`InstallGuardScenarioTest.an app quarantined once can still be approved and installed later`.
