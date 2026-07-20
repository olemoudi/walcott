# TODO — reliability backlog

Remaining reliability gaps identified after auditing the enforcement path, the sync channel and
the failure handling. Ordered by the size of the risk each one closes, with the work sketched
far enough to start cold.

Already covered, for context (don't redo): enforcement loop survives unexpected exceptions
(`runLoopResilient`), a poison message can't wedge the sync cursor, snapshot convergence +
re-emit + TTLs + idempotent application over a lossy channel, ~30 min Doze-resilient check-in
(`HeartbeatAlarm`), watchdog + boot/update restarts, fail-closed on revoked usage access,
suspension failures logged, parent alerts (battery, network location, enforcement, usage access,
mock GPS, wrong PIN, never-reported, stale), self-healing icon sync, `allowBackup=false`.

Shipped in v0.10.0: enforcement self-test on the heartbeat (`EnforcementSelfTest` verifies
`isPackageSuspended` agrees with `RuleEngine.blockedPackages`, re-asserts and reports
`ChildSnapshot.enforcementGaps`; the alarm also restarts `EnforcementService`), clock-tamper
detection (`ClockGuard` compares the ntfy server timestamps against the local clock,
replay-safe, one-shot alert with hysteresis), remote diagnostics (`RemoteAction.DIAGNOSE` →
`DiagPayload` health report in its own message kind, log tail trimmed by `DiagFit`), parent as
update canary (`ParentSnapshot.parentVersionCode`; children install only up to the parent's
build, `UPDATE_NOW` overrides), and child-side channel health (every received message stamps
`lastChannelOkMs`; the child home admits "no connection with your family since…" after 2 h).

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
