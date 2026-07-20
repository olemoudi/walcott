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

The backlog is empty.
