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

## Emulator notes that cost time

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
