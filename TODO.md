# TODO

Nothing outstanding on the domain viewer. What was in flight on 2026-07-30 shipped as **v0.22.0**
(versionCode 63); the notes below are kept only so none of it gets redone or re-litigated.

## Shipped in v0.22.0 — the domain viewer's send path

- **Fixed send bar.** `DomainMonitorScreen` pins one bar outside the `LazyColumn`, aggregating the
  selection across every app group. Verified on the emulator: 9 ticks in Chrome + 1 in
  `com.google.android.gsf` read "Send the 10 selected domains", and the bar stayed put at the
  bottom of a long list.
- **Chunked delivery with acks and bounded retries** (`DomainDelivery`, `DomainBatch`,
  `DomainInbox`). Every slice self-describes (`batchId`, `index`, `chunks`), so each publish *is*
  the report of what remains unconfirmed — no manifest handshake that can wedge, any arrival order.
  The child resends every 20 s (the parent is standing right there), sends the next slices on the
  back of each confirmation rather than waiting out the interval, and **gives up after 8 rounds
  with nothing confirmed**, saying so on its screen.
- **Parent review flow.** Highlighted home card → review screen with prunable list and the two
  scope questions (family vs this child, this app vs any app) → rules written automatically, or
  discarded for good.
- **`ChildOverrides.domainAppRules`**, so "this child + this app" is expressible at all.
- **A real bug fixed:** `SnapshotFit` degraded trail → history → apps and then returned its last
  attempt *unmeasured*, assuming everything left was small. A parent-chosen domain list broke that
  assumption: a large selection meant a publish over the 3800-byte cap → HTTP 413 → **the child
  silently disappears from the parent**. Every branch is now measured, and asks are cut last.
- **A bug in the first cut of this feature, caught before shipping:** `MAX_ATTEMPTS` counted total
  publishes. A batch offers 2 slices per message, so a 40-slice selection needs 20 publishes and
  would have been abandoned half-delivered on a perfectly healthy channel. It now counts rounds
  *since the last confirmed slice*, and a confirmation resets the patience.

Tests: 595 JVM (0 failures), of which 42 are new across `DomainDeliveryTest`, `DomainInboxTest`,
`DomainScopeTest` and three added `SnapshotFitTest` cases for the 413 path.

**Now legacy, deliberately kept:** `ChildRequest.KIND_DOMAINS` + `DomainAsk` + `DomainsAskCard` are
the v0.21.0 mechanism. New children never use them, but a v0.21.0 ask can still be in flight across
the update, and one was — it rendered correctly on the emulator during this verification. Delete
only once no 0.21.0 child remains.

## SOLVED (0.63.0): the "known e2e failures" were the emulator falling asleep

The four `:parent-sim:e2eTest` scenarios previously recorded here as pre-existing failures —
`RuleEventScenarioTest` (all 3) and `GrantScenarioTest > a replayed snapshot does not grant the bonus
a second time` — were **not product bugs and not harness logic**. They were a sleeping screen.

`EnforcementService`'s loop deliberately PARKS while the screen is off (`screenOn.first { it }`): it
suspends nothing new and burns no wakeups, which is right on a real phone and fatal in a test. The
`walcott-spike` AVD dozes off within a minute of the last input, so any scenario running more than a
minute into a session was waiting on a phone that was not evaluating rules at all. Every symptom
followed from that: no suspension, no rule events, no `appliedPolicyVersion` moving, and — worst —
`assertDeviceNever` passing without having tested anything.

It presented as "pre-existing" purely because scenario order is stable: the classes that run late in
an alphabetical sweep are the ones that find a sleeping phone.

The fix is in the harness, in three places (see `ChildDevice.keepAwake`/`nudgeAwake`):

- `DeviceScenario.pairFreshFamily` sets a 24-hour `screen_off_timeout` and wakes the device.
- `awaitDevice`, `assertDeviceNever` and `childEventuallyReports` send `KEYCODE_WAKEUP` on **every**
  poll. Once at the start is not enough — a 60-second wait outlives the doze either way.
- `svc power stayon true` is set as well, and does nothing on its own: an emulator reports
  `mStayOn=false` because nothing is plugged into a virtual phone. Even the long timeout is not
  enough by itself, which is why the nudge lives inside the waits.

After it: **79/79 e2e scenarios pass on the walcott-spike AVD.** If a schedule or budget scenario ever
goes red again, check `adb shell dumpsys power | grep mWakefulness` before suspecting the product.

## Emulator notes that cost time

- **`adb root` silently breaks `cmd notification post`.** With adbd running as root the command
  still prints `posting: Notification(...)` and returns success, and the notification never lands —
  it is not in `dumpsys notification` and no listener is told about it. `adb unroot` (shell uid
  2000) and it works again. It cost half an hour on 2026-08-17, because `adb root` had been used
  earlier in the session to restart the app (see the force-stop note below) and nothing connects
  the two. If a notification-log scenario says the device recorded nothing, check `adb shell id -u`
  before anything else.

- **`am force-stop` does not kill the app on the Device Owner emulator.** The system brings it
  straight back (foreground service + device owner), so anything you changed on disk expecting the
  next process to re-read it is instead ignored by the process that never died — `pidof dev.walcott`
  still answers. Verifying a cold start (or a hand-edited `files/blocklists/state.json`) needs
  `adb reboot`, not a force-stop.
- **`raw.githubusercontent.com` answers `429 Too Many Requests` after a dozen multi-MB downloads,
  for hours.** It is the host behind five of the eight blocklists, so a testing session that pulls
  them repeatedly locks itself out of exactly what it was testing. The GitHub *API*
  (`/repos/.../contents/<dir>`) is a different bucket and still answers with file sizes, which is
  enough to check a path exists and to estimate an entry count (~17.9 bytes per domain). The 429
  body is 199 bytes of prose, which is what `BlocklistStore.looksWrong` exists to refuse.
- There is no `nslookup` on the API 35 image. `ping -c1 <host>` answers `unknown host` for an
  NXDOMAIN, which is what the filter returns, and shell traffic does go through the tun.

- `walcott-spike` loses its **active device-admin** record across reinstalls: the Device Owner
  package check still passes and `setPackagesSuspended` still works, but anything validating the
  admin *component* fails with `SecurityException: Admin ... does not exist`, so
  `setAlwaysOnVpnPackage` is refused and **the DNS tunnel never establishes**. It looks exactly
  like a product bug. Cure:

      adb shell dpm set-active-admin --user 0 dev.walcott/dev.walcott.WalcottAdminReceiver

  Check with `adb shell dumpsys device_policy | grep -A4 "Enabled Device Admins"`. Since v0.21.0 the
  app logs the refusal itself (`WalcottVpn` in `/data/data/dev.walcott/files/debug-log.txt`).
- **The monitor needs a runnable app to watch.** A seeded policy with no `assignments` blocks
  everything, so the browser you are trying to observe is suspended and makes no lookups at all.
  Seed `assignments` with the target package (and no budget for its category) first.
- Driving the child's PIN-gated screens costs a PIN round trip each time, because backgrounding
  snaps the child device back to its home *by design*. Seed a known PIN:
  PBKDF2-HMAC-SHA256, 120k iterations, 256-bit, base64 hash + salt into `pinHash`/`pinSalt`.
- `--es child_domains "pkg=Label=a.com,b.com"` on `PolicySeedReceiver` drives the parent's whole
  domain flow on one emulator, through the real `DomainInbox`; add
  `--ez child_domains_partial true` to hold the last slice back and check the "not actionable yet"
  case.
- `--es child_request "pkg:Label:minutes:reason"` seeds one pending extra-time request (pair it
  with `--es child_usage "pkg=SECONDS"` to give the card's "already X today" line something to
  report). Add `--ez child_request_notify true` to also post the notification: its Approve/Deny
  actions can't be fired with `am broadcast`, because `RequestActionReceiver` is — rightly — not
  exported, so tapping them in the shade is the only way to exercise the real PendingIntent path.

---

# Reliability backlog

Empty. The audit items below all shipped; kept for context so none of it gets redone.

Shipped in v0.37.0 — a second audit pass, mostly about the recovery door:

- **A family could have no PIN at all, and then there was no door.** Nothing ever required one:
  the wizard doesn't ask, and `PinGateScreen` only creates one on the way into parent mode.
  `WalcottRepository.verifyPin` returns false when `pinHash` is null, so on such a family the
  child's "Release this device" asked for a PIN and rejected every answer forever — earning
  escalating lockouts and firing "wrong PIN" alerts at the parent for a door that was never
  going to open — while the panic screen told the child their parents could free the phone
  instantly. Only the 24-hour countdown was real. Now: no enrollment code is handed out until
  the family has a PIN (`EnrollmentSection`), the home's setup checklist carries it as a step
  and reappears for families that predate the gate, and `PinResult.NotSet` lets every screen
  say "there is no PIN" instead of "wrong PIN" — including the app lock, which could otherwise
  shut a parent out of their own app with a gate that had nothing behind it.
- **The parent PIN can be read back on the parent phone** (`FamilyIdentity.pinPlain`), so
  forgetting it no longer costs a policy change that has to reach every child before any of
  them can be released again. Device-local by construction: `:core-sync` never names that
  field, no snapshot carries it, and the backup rebuilds the identity rather than restoring it,
  so the plaintext reaches neither the children nor the backup file — only the PBKDF2 hash
  travels, as before. Revealing asks for exactly what resetting asks for (`pinResetPath`),
  because it grants exactly as much. Families predating it get their copy the next time the PIN
  is typed correctly, the same trick the local-backup key already used.
- **Requests never expired, which left the child unable to ask again.** `createdAtEpochMs` was
  recorded and never read — commands expire after 7 days, "locate now" after 30 minutes,
  requests never. The child's home refuses to send a second request while one is pending, so an
  unanswered one killed that app's button for good, and pinned a week-old question above
  everything current on the parent's home. `SyncEngine.REQUEST_TTL_MS` is 48 h (a weekend away
  is not a refusal); the child retires its own on the heartbeat and says so, and the parent's
  lists drop them too, since an older child build re-sends forever.
- **The accessibility backend queried AppOps on every window change.** `AppBlockerService` asked
  whether usage access was still granted once per `TYPE_WINDOW_STATE_CHANGED` — a binder round
  trip in the hot path of the fallback backend, on exactly the phones that aren't Device Owner.
  Cached for 10 s, like the Device Owner loop already did.
- **Changing the PIN froze the dialog for tens of seconds** (~30 s measured on the emulator):
  `setPinEverywhere` ran PBKDF2 at 600k plus three encrypt-and-write backup cycles per family
  inside the Save button's busy state. The PIN is saved synchronously; the backup work now
  follows on its own, and a process death before it lands is repaired by the next correct PIN
  entry, which is where legacy families get it anyway.

Shipped in v0.36.0 — four holes from a fresh audit, all of them silent failures:

- **A socket that dies without saying so.** OkHttp sends no WebSocket pings by default, and
  `NtfyTransport` only reconnects from `onFailure`/`onClosed` — neither of which fires when a
  carrier or NAT drops the connection without a FIN, which is the normal way a mobile socket
  dies. Nothing else reopened it: `connect()` runs at start-up and on pairing, the parent has
  `ParentPollWorker` as a fallback and **the child has none**. So the child kept publishing over
  HTTP (looking perfectly healthy to the parent) while rules, granted time and every remote
  command — `DENY_PANIC` and a reset PIN included, i.e. both escape hatches — stopped arriving
  until the process restarted. Fixed with `Http.webSocketClient` (30 s pings) plus a backstop on
  the heartbeat: an hour of silence rebuilds the socket (`ChannelHealth.needsReconnect`), and the
  `since=` cursor replays whatever was missed.
- **An emergency release that stopped halfway was terminal.** Step 4 stops the foreground
  service, so the process is killable from there, while Device Owner isn't dropped until step 6.
  In between, the device is no longer a child — so the settings screen no longer offers the
  release button that would retry — and a phone owned by an app that manages nothing needed a
  factory reset. `PanicRelease.finishIfInterrupted` now runs on every start-up of a released
  device: unsuspends whatever is still suspended, clears the restrictions, drops Device Owner.
- **A release already earned could still expire.** The twelfth notice is banked and published
  before the teardown runs, and `evaluate` checked the deadline first — so an interrupted release
  that then went offline for three hours voided a countdown the child had already served in full.
  `PanicProtocol.earned` is now checked ahead of everything, and `expirePanicIfOffline` finishes
  such a request instead of killing it.
- **Crashes left no trace.** No `setDefaultUncaughtExceptionHandler`, so the one failure worth
  investigating on a child device was the only one missing from the log tail that a remote
  DIAGNOSE ships to the parent. `DebugLog.crash` writes on the calling thread (the executor the
  rest of the log uses is never scheduled again once the process is dying) and then lets Android
  take its course.

Also from that audit, on the parent's side: the request card answers with any amount rather than
only the one asked for (the wire always carried `grantedMinutes`; the button hard-coded it), says
what the child has already had today, and the notification carries Approve/Deny — suppressed when
the app lock is on, since a button in the shade is not behind that gate. Extra-time notifications
are per-request now; a fixed id meant a second child's question replaced the first and was
simply never seen.

Already covered: enforcement loop survives unexpected exceptions (`runLoopResilient`), a poison
message can't wedge the sync cursor, snapshot convergence + re-emit + TTLs + idempotent
application over a lossy channel, ~30 min Doze-resilient check-in (`HeartbeatAlarm`), watchdog +
boot/update restarts, fail-closed on revoked usage access, suspension failures logged, parent
alerts (battery, network location, enforcement, usage access, mock GPS, wrong PIN, never-reported,
stale), self-healing icon sync, `allowBackup=false`.

Shipped in v0.10.0: enforcement self-test on the heartbeat (`EnforcementSelfTest` verifies
`isPackageSuspended` agrees with `RuleEngine.blockedPackages`, re-asserts and reports
`ChildSnapshot.enforcementGaps`; the alarm also restarts `EnforcementService`), clock-tamper
detection (`ClockGuard` compares the ntfy server timestamps against the local clock,
replay-safe, one-shot alert with hysteresis), remote diagnostics (`RemoteAction.DIAGNOSE` →
`DiagPayload` health report in its own message kind, log tail trimmed by `DiagFit`), parent as
update canary (`ParentSnapshot.parentVersionCode`; children install only up to the parent's
build, `UPDATE_NOW` overrides), and child-side channel health (every received message stamps
`lastChannelOkMs`; the child home admits "no connection with your family since…" after 2 h).

Shipped in v0.11.0 — parent backup / restore, closing the last item (#1). The design is a
hybrid of the two options that were on the table: new families generate their signing key in
software (`FamilyIdentity.parentPrivateKeyB64`) so a backup can export it — it sits beside the
family key, which was always in the DataStore, so the at-rest exposure doesn't change class —
while legacy Keystore families get a fresh recovery keypair per backup plus a `RotationCert`
minted by the still-alive Keystore key ((b)'s re-key, but signed *in advance*, so it can never
become a hijack vector: only the key children already trust can vouch for a successor).
`FamilyBackup` seals everything (keys, topic, server, full `PolicySettings`) with
PBKDF2-600k + AES-GCM under a parent-chosen passphrase; the parent settings card saves the
file via SAF or the share sheet, with an optional fire-and-forget mode that rewrites the file
on every rule change (KDF output cached, passphrase never stored); the mode-select screen on
a fresh install restores it, resumes the version counter above the backup's, and republishes —
children adopt the rotated key from the envelope and never need to be touched.

v0.11.0 also closed a pre-existing gap the security review surfaced (it predated the backup
work): children used to apply the rules from any *validly signed* parent snapshot regardless
of its `version`, so someone holding the topic + family key (e.g. a removed child device)
could replay an old captured envelope to roll rules back to a laxer past state. Now the child
gates rule adoption on version monotonicity (`SyncEngine.adoptsPolicy`), with two deliberate
escape hatches: a verified key rotation rebases the baseline (a restored parent's counter may
legitimately restart lower), and a fresh pairing resets it (the QR in hand is the trust
bootstrap). Same-key restores carry no rotation, so `restoreBackup` leaps the counter far
past the backup's version instead. Commands/resolutions/bonuses were already idempotent by
id and keep processing on every message, version aside.

## Worth doing eventually (from the test-suite audit, v0.20.1)

- **Instrumented coverage for the Android-bound half**: the `EnforcementService` loop, the
  `SyncManager` transport and the workers are at 0% and only covered by hand today. The
  scaffolding now exists (`app/src/androidTest`, 14 tests, debug signed with the release key so
  it installs over a Device Owner).
- **`SyncProtocol` at 74% branch** — the largest remaining gap in pure code.
- **Timezone changes.** The usage counter is keyed by `LocalDate`, so a child crossing zones
  moves their day boundary. Untested and unreasoned-about.
