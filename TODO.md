# TODO — reliability backlog

Remaining reliability gaps identified after auditing the enforcement path, the sync channel and
the failure handling (as of v0.9.1). Ordered by the size of the risk each one closes, with the
work sketched far enough to start cold.

Already covered, for context (don't redo): enforcement loop survives unexpected exceptions
(`runLoopResilient`), a poison message can't wedge the sync cursor, snapshot convergence +
re-emit + TTLs + idempotent application over a lossy channel, ~30 min Doze-resilient check-in
(`HeartbeatAlarm`), watchdog + boot/update restarts, fail-closed on revoked usage access,
suspension failures logged, parent alerts (battery, network location, enforcement, usage access,
mock GPS, wrong PIN, never-reported, stale), self-healing icon sync, `allowBackup=false`.

---

## 1. Parent backup / restore — the biggest single point of failure

**What fails today.** The family key and the parent's signing key live only on the parent's
phone. If it is lost, broken or factory-reset, the family is orphaned forever: children keep
enforcing the last policy, but nobody can ever control or un-enroll them again (the gate asks
for a PIN that can no longer be changed). The only way out is factory-resetting every child.

**Catch — needs a design decision before coding.** The parent's ECDSA key lives in Android
Keystore (`ParentKeystore`) and is **non-exportable by design**. Supporting restore means
choosing between:
- (a) keeping a passphrase-encrypted copy of the private key that can be exported — simple to
  restore, but it creates a new attack surface for material that is hardware-protected today; or
- (b) a re-key protocol where children learn to trust a new parent key — no exported secret, but
  materially more complex, and it must not become a family-hijacking vector (children verify the
  parent's signature precisely to prevent that).

**Effort.** Large, plus the security decision above. Do not start until (a)/(b) is chosen.

---

## 2. Enforcement self-test — catch "looks healthy, isn't blocking"

**What fails today.** `Enforcer` now logs packages the OS refuses to suspend, but nothing
actively verifies that blocking actually works. The scariest failure class is the silent one:
the service is running, the rules are right, and yet an app isn't really suspended.

**Approach.** Piggyback on the existing 30-minute `HeartbeatAlarm`: pick a package that *should*
be suspended right now (from `RuleEngine.blockedPackages`) and confirm the system agrees
(`isPackageSuspended`). On a mismatch, report it to the parent (new field in `ChildSnapshot`, or
reuse the health-alert path in `applyChildSnapshot`) and re-assert. Also have the alarm ensure
`EnforcementService` is running — it is a wake path WorkManager doesn't reliably provide in Doze.

**Effort.** Low. Everything it needs already exists.

---

## 3. Clock-tamper detection

**What fails today.** If the parent didn't enable the date/time restriction
(`DeviceRestrictions.KEY_DATETIME`, on by default but optional), a child can move the device
clock and walk straight past bedtime and daily budgets.

**Approach.** The data is already in hand: `NtfyTransport` parses the server timestamp (`time`)
of every message and passes it as `timeSec`. Compare it against the local clock; if the skew
exceeds a threshold, alert the parent (same one-shot + hysteresis pattern as `HealthAlerts`) and
optionally fail closed. Keep the pure decision in `core-sync` so it is unit-tested.

**Effort.** Low. Closes a real bypass.

---

## 4. Remote diagnostics — "send me a health report"

**What fails today.** When a child misbehaves, diagnosing it means physically holding the phone.

**Approach.** A new `RemoteAction` whose reply is a compact report: enforcement backend, Device
Owner state, usage access, location providers, recent suspension failures, update error, battery,
and the last N debug-log lines. Send it as its own message kind rather than growing
`ChildSnapshot` — reuse the pattern already proven by the icon exchange (`IconPayload`,
`SyncProtocol.encodeChildIcons`), including packing under the ntfy size cap.

**Effort.** Medium. High practical value for an app administered at a distance.

---

## 5. Parent as the update canary

**What fails today.** Auto-update ships every release to the whole fleet of children with no
staging. One bad build can break enforcement everywhere at once.

**Approach.** Have the parent publish its own `versionCode` in `ParentSnapshot`, and let the
child install a new version only once the parent is already running it. The parent's own update
is user-confirmed (not silent), so the parent becomes a natural canary for free. Keep a manual
override so a deliberate forced update still works.

**Effort.** Medium. Protects against the "I broke every child at once" scenario.

---

## 6. Channel health on the child side

**What fails today.** The parent sees check-in staleness; the child has no idea it has been
unable to reach the family for hours, which makes a dead channel look like a dead app.

**Approach.** Track the last successful publish/receive and surface an honest line on the child
home ("no connection with your family since…"). The `lastPublishAtMs` field added for the
heartbeat throttle is most of the input already.

**Effort.** Low. Moderate value.

---

## Suggested order

1. **#2 + #3 together** — both cheap, both build on what already exists (heartbeat, transport
   timestamps), and together they close the two remaining silent-failure/bypass holes.
2. **#5** — the fleet-wide blast radius of auto-update is the next biggest risk.
3. **#4** — makes everything else diagnosable remotely.
4. **#1** — highest value overall, but blocked on the security trade-off above.
