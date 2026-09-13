# TODO

Nothing outstanding on the domain viewer. What was in flight on 2026-07-30 shipped as **v0.22.0**
(versionCode 63); the notes below are kept only so none of it gets redone or re-litigated.

## Shipped in v0.115.0 — freeing a phone, by default and in plain sight

- **"Remove" frees their phone by default.** The remove dialog's "also release their phone" box
  started unticked, so the easy path removed a member and left their phone enforcing the family's
  rules with nobody able to change them. It is ticked now wherever the phone can be released
  (`RemoteAction.canRelease`), never on a build that cannot, and the tap re-checks it. Asked for by
  ole on 2026-09-13, deliberately left for the next release — this one.
- **A release you can see waiting, and one that runs out is said.** Asked by ole the same day ("al
  liberar un hijo… desaparece, no se queda como pending"). The mechanism was already right — the
  phone's row stays until its acknowledgement — but nothing on screen said so: the pending line
  read the raw `release_device`, the row read like an abandoned phone with the same three offers,
  and after the week's TTL the line simply left the list while the phone went on being managed.
  Now: `SyncEngine.releaseStatuses` (pure, `PendingOpsTest`) says pending or run out per device;
  the orphan row and the member's remote-fix card say "being freed" with a cancel, or "never
  confirmed" in red with free-again; `ParentCheckAlarm` → `announceUnconfirmedReleases` posts one
  status-channel alert and one wall line per release (`releaseUnconfirmedNotified`). Resending
  is in the app, behind the irreversible confirmation — deliberately not a notification button.
  Not proven on a device: the expiry is a week away and no scenario warps the parent's clock.

## Shipped in v0.114.0 — the phone that is helped, reviewed end to end

A review of the assisted-adult mode on 2026-09-13, asked for by ole ("revisa la parte destinada a
supervisar adultos"), then "arregla todo". Seven faults read in the code and two more proven on the
emulator. New features the review suggested (call buttons, remote brightness, per-member lists, a
wellbeing alarm) were NOT part of it and are listed at the end.

**An adult inherited the family's rules.** `addChild` gave an adult three overrides (location, the
accident-proofing, the ringer) and left every rule null, and null means inherit: a grandparent added
to a family with a bedtime got the bedtime, while the sheet that created them said "no bedtime and no
limits" and their own screen had nowhere to say why an app stopped opening. Now
`PolicySettings.withMember` gives an adult `ChildOverrides.withoutFamilyRules()` — bedtime,
screen-free windows, both budgets, per-app limits, typed domains and domain rules all empty — and
`separateAdultRules` does the same once for adults already enrolled, field by field so a rule set for
that adult on purpose survives (flag `adultRulesSeparated`, run at parent start-up for every family).
Public blocklists are family-only on the wire and still reach an adult; a per-member switch is a
feature, not this fix.

**0.113's install window was unreachable for adults**, the members who start with installs blocked:
the quick actions sheet was hidden for them. It opens for everybody now; for an adult the minutes and
pause rows are left out (bedtime was already conditional on there being one).

**"Sent. They can see it now." was said before anything was sent.** The help card read the ask from
the phone's own pending list, so offline it said "sent" under "this phone can't reach your family".
`SyncState.askReceipts` holds the asks the relay confirmed; while a help ask is unconfirmed EVERY
child publish is a receipted one (`HelpAsks.unconfirmed`), so the heartbeat or a reconnect turns
"Sending…" into "Sent" without a retry path of its own. When a parent taps "I've helped", the phone
shows "Your family has seen it" (the resolution notice, which the assisted home never rendered).

**The parent's alert was easy to lose.** It went out once, on the channel named "Extra-time
requests" — silencing a teenager's requests silenced a grandparent's call for help — without a DND
bypass. Now its own `walcott_help` channel asking to bypass DND, and `HelpAsks` reminders: up to three,
15 min apart, timed from this phone's notification (never the asking phone's clock), driven from
`ParentCheckAlarm` after the poll so an answer given on another parent's phone stops them.

**Locked uninstalls stopped the family's removals (proven on the AVD).** `DISALLOW_UNINSTALL_APPS` is
in the adult defaults, and the platform applies it to the Device Owner's own `PackageInstaller`
uninstall — logcat: `PackageManager: User is restricted: no_uninstall_apps`; the app only logged
`status=-1`. So "remove this app" and the guard's removal of an unapproved one did nothing on exactly
those phones. `silentUninstall` lifts it for the one request and puts it back (not during a release);
the platform checks it when it takes the request. New scenario
`InstallGuardScenarioTest.the parent removes an app even when the phone locks uninstalling`, red on
0.113, green now.

**Locking brightness froze a dark screen (proven on the AVD).** The restriction has no side effect:
brightness 1 stayed 1. Like location and auto-time, the setting is put in a safe state first:
`DeviceRestrictions.MIN_LOCKED_BRIGHTNESS` (25/255, about half-way along Android's perceptual slider)
for a manual screen only — adaptive is the phone choosing. Scenario
`AssistedScenarioTest.a screen that is almost dark is made readable before its brightness is locked`.

**The ringer guard's Do Not Disturb half could never run.** `AudioGuard.liftDoNotDisturb` needs DND
access, the manifest did not declare `ACCESS_NOTIFICATION_POLICY` (so Walcott was not even listed in
that setting) and nothing asked for it. Now declared, asked for by the guided setup as
`DeviceRequirement.DND_ACCESS` where the ringer guard is on (not critical), and the guard also reacts
to `ACTION_INTERRUPTION_FILTER_CHANGED` rather than waiting for the watchdog. Not verified on a phone
with DND access granted: the AVD suite does not grant it.

**What the phone said about itself was worded for a child.** Android's "managed by your administrator"
text said "open it to ask for more time" on an adult's phone. `DeviceRestrictions.applySupportMessages`
picks the wording by `PolicySettings.isAssistedMember`, from the enforcement service (on a kind change)
and the watchdog (for a change of language).

**Accounts.** `DISALLOW_MODIFY_ACCOUNTS` blocks adding an account as well as removing one; that is the
restriction working, not a fault, so its description now says to switch it off while signing in.

Suggested by the review and not done: call buttons on the adult's home; phone-health lines for
support (airplane, mobile data, storage); setting brightness and timeout remotely
(`setSystemSetting`); per-member blocklists (scam list on for adults); an opt-in "not used for N
hours" alarm; location attached to a help ask; call screening; a no-reset enrollment for adults.

## Shipped in v0.113.0 — installs for a setup afternoon, asking for an app the way that works, and a note that touched the search field

Three things ole asked for on 2026-09-13, after enrolling a real phone with 0.112.

**"Let it install anything", from the parent's quick actions.** Setting a phone up means a dozen apps,
and approving each one is not what anybody wants to be doing that afternoon. The member's quick
actions sheet offers 30 min and 2 h; the phone opens the same blanket window a PIN typed on it opens
(`SyncManager.allowInstallsFor`), so whatever is installed from Play stays and the install guard
judges none of it. While a window is open — or on its way — a card at the top of the sheet says until
when and has **Close now**.

- New command `RemoteAction.ALLOW_INSTALLS`, the minutes in `arg`. The window ends when the parent
  meant it to (`allowInstallsRemainingMs`: a command that took twenty minutes to arrive opens ten), a
  command older than 30 minutes is refused (`ALLOW_INSTALLS_TTL_MS`), and a parent clock ahead of the
  phone never lengthens it. Gated on `ALLOW_INSTALLS_MIN_CHILD_VERSION = 162`: an older phone shows
  the row disabled, saying it has to update.
- **Close now** is `REAPPLY_POLICY`, which every build already obeys by closing any window and putting
  the block back — so it also closes a window a child opened with the PIN, or a pushed install's. A
  still-queued open is withdrawn first (`SyncManager.closeInstallsOn`), or it would reopen what was
  just closed on its way in.
- The card reads the window from the phone's own snapshot, which already leaves the nightly update
  hour out, plus what is still queued, so it appears on the tap. Offered only where installing is
  held back at all: blocked, or watched in the guarded mode.
- `RemoteInstallWindowScenarioTest`: the block lifts, a fixture installed in the window is still
  installed and not suspended after closing, the block comes back, and a forty-minute-old half hour
  opens nothing.

**The override note sat on the search field** in Apps and limits, in both of its forms (the family's
"Martín Jr has a custom rule…" and the one-member banner): the column they live in has no spacing of
its own. The gap is on the note itself, so a family with no overrides gets no blank strip. The same
fault was in the location settings card, between the interval chips and the battery warning. The
lists (limits, web filter, protection) space their items and were fine. Checked on the emulator in
dark mode.

**"Location only while using the app" on a new child is by design**, and nothing asks to fix it:
`LocationPolicy` denies background location on purpose, because an admin-forced grant posts a
permanent notice, and tracking samples inside the location-typed foreground service. The manifest
comment said the opposite, which is what made it look like a bug; it no longer does.

**"Ask for an app" teaches the Google Play share instead of asking for a name.** A written name
reached the parent as a sentence they could approve and do nothing with — approving it installed
nothing — while an app shared from its Play page arrives as that exact app, which the parent installs
from their phone (`ShareInstallActivity`, `requestAppInstall`). So the child's card now opens a guide
(`AskAppGuideSheet`): three numbered steps with the pictures the child will see (Play's icon, the ⋮
and Share buttons, Walcott's icon as the share sheet shows it), an optional search box, and "Open
Google Play", which lands on the results for the search or on Play's front page
(`PlayIntents.search`), and falls back to the Play website — which shares the same way from a browser
— when Play is not there. The text dialog is now only "Ask for something else". The parent side still
reads written app asks (`ChildRequest.KIND_APP`), because a child on an older build can still send one.

**Verification:** `./gradlew test` green. On `walcott-mapview`, in dark mode: Apps and limits with the
family note spaced from the search field; the parent's quick actions with the install row (disabled
for a seeded child that reports no version, with its "has to update" line); the child's guide, and its
button opening the Play website in Chrome on an image without Play. Device scenarios for installs,
guard, update window and policy: 25/25, including both `RemoteInstallWindowScenarioTest` cases. Not
re-captured after changing the chip labels to "30 min" / "2 h": the emulator was left as a child by
the suite. For the release: `./gradlew test` and Lint green, the release APK shrunk to 9.0 MB, the
enrollment checksum `match=true` on 0.113, and `:parent-sim:e2eTest` 136/136 in 43 minutes. Destructive
suite: one pass, not two, because 0.113 touches no release path and ole asked for suites scoped to
what a change applies to: 8/8.

## Prepared for v0.112.0 — the second review (not released)

ole asked what important problems the app still has, and then for the plan to be carried out.
Three cuts this time, so as not to walk the 0.107 review again: what happens on real phones over
months; a concrete threat model — a child, a sibling, and a photograph of the pairing QR taken
while the parent shows it; and what a family that is not the author's meets on its own. Every item
was read back in the code before it counted. The plan with all of it is
`/home/ole/.claude/plans/puedes-hacer-una-revisi-n-dapper-hollerith.md`.

**No family could enroll a child's phone from the QR, from 0.107 to 0.111.** The enrollment QR
carries a certificate checksum, and Android's provisioning reads the downloaded APK with
`GET_SIGNATURES` and hashes `PackageInfo.signatures` — which, for an APK with a v3 rotation
lineage, the platform fills with the OLDEST certificate "so that programmatic checks keep working".
The app hashed `apkContentsSigners`, the current one. Every QR shown since the key rotation named a
certificate provisioning never sees, and a factory-reset phone refused to set up with nothing
saying why. Nothing tested it: the harness makes Device Owner with adb. The checksum is now a
constant, `DeviceOwnerProvisioning.PUBLISHED_SIGNATURE_CHECKSUM` (the original certificate, which a
lineage keeps for ever), pinned by a test, and proven on the AVD with the debug hook
`--es mode provisioning_checksum` (`match=true`). The release checklist runs that hook, and
`docs/signing.md` explains why the QR names the old key.

**What a family meets on its own:**

- **A second parent restoring the backup took the family away from the first, silently.** The
  restore leaps the version counter a million, every child follows it, and the first phone's
  edits are refused for ever while its screen says "sending". The parent now listens to parent
  snapshots on its own topic, and one from another phone a whole leap above its own
  (`SyncEngine.parentSuperseded`, keyed by a per-phone `parentInstanceId`) raises an urgent alert
  and a home card with "Take it back" (`takeoverVersion`) or "Leave it with them". A restore first
  reads the last hour of the topic over HTTP and, if a phone is live there, asks before taking the
  family over (`RestoreResult.ALREADY_MANAGED_ELSEWHERE`, `TakeoverDialog`); it fails open without
  a network, because the lost-phone case is the one restoring exists for. The README says one
  parent phone manages a family.
- **A replaced child phone left a ghost row that every screen and every action used.** A factory
  reset cannot keep the deviceId, so the replacement was appended beside the dead phone and 26
  `firstOrNull { childId }` lookups took the dead one. The ViewModel now serves one device per child,
  the one heard from most recently (`SyncEngine.currentDevices`); earlier phones get a card on the
  member's page to retire or free them, and a "checked in from a new phone" notice. (Usage history
  was never lost: it is filed by childId.)
- **"Change device mode" left the phone enforcing a family it no longer belonged to** — worse than
  the review said. A blank identity is UNSET and UNSET enforces, so the transition never flipped the
  flag that runs the hand-back at all. `resetDeviceMode` now stops the loop, hands the device back,
  unlinks and drops the rules; `ChangeModeScenarioTest` proves it at bedtime (new debug mode
  `change_mode`). The first version of that fix had a bug of its own, and the device suite
  is what found it: it stopped the enforcement service for the hand-back and nothing started it
  again — child to UNSET does not change whether a phone enforces, so the mode observer saw no
  transition. The scenario passed; the four after it (the curfew's tunnel, the low-battery word)
  failed on a phone whose loop was not running, until the watchdog brought it back. The reset now
  restarts the service, and the scenario pairs again afterwards and waits for the next bedtime to
  bite, so a stopped loop fails the scenario that stopped it.
- **Pairing said "couldn't read that code" for every mistake and "linked" for some failures.** The
  scan is classified (`CodeScan`: pairing code, enrollment code, download link), a confirm dialog
  names the child and the family before joining, and `pairAsChild` waits for the relay's receipt and
  says so when it did not come (`PairResult.PAIRED_NO_CONTACT`).
- **Turning the phone, changing font size or dark mode threw a parent back to the home screen and
  the app lock.** `MainActivity` handles those configuration changes, and the navigation cursor is
  `rememberSaveable`. The app-lock flag deliberately is not (saved state outlives the process).

**What a photograph of the QR bought, closed where it can be closed locally** (the structural fix is
D1, pairing v2, in the plan):

- A forged child snapshot at `Long.MAX_VALUE` froze a sibling's row for ever. `mergeChild` refuses a
  version jump larger than what a phone could publish in the time since it was last heard
  (`maxChildVersionJump`: 10 000 plus one a second). A speed bump, and said so in the KDoc.
- A forged ack retired any command, the refusal of an emergency release included; now only a
  command addressed to that device. A forged `RELEASE_DEVICE` ack deleted a sibling; now it must
  answer a release this parent issued. `panic == null` cancelled the alert with no version guard;
  it has the guard now, and a countdown that stops by any means other than the parent's own refusal
  says so out loud (`notifyPanicStopped`).
- Approve and "Allow it to stay" fired from the parent's lock screen; both need an unlocked phone
  (`setAuthenticationRequired`), and "Allow" answers to the app lock like the other answers.
- A redeemed rescue code was a wall line that aged out in six hours. It is an urgent notification
  now, and child events travel for 48 hours.
- A gzip of a few kilobytes inflated to megabytes on every phone, on every reconnect; inflation is
  capped at 100× the message cap. A reconnect cursor is floored at twelve hours.
- The relay refusing this phone's messages (a rate limit a household shares, or an outage) was a
  line on a settings card; it is an alert now, worded for the rate-limit case.
- The emergency-release alert is alarm-category and ongoing while the countdown runs, and asks to
  bypass Do Not Disturb (granted only with notification-policy access, which is not requested).

**The child, closed a second time:** `dataExtractionRules` exclude everything from cloud backup and
device transfer (allowBackup alone stops only the cloud half from Android 12); `BootReceiver` and
`AppUpdateWindowReceiver` are no longer exported; work profiles and private space are refused under
the add-user switch; the best-known public resolvers are routed into the tunnel by IPv4 address
(`PublicResolvers`), so asking one directly, or a browser's secure DNS pointed at one, goes through
the filter and the curfew; the clock is watched on the phone itself when nothing arrives from the
relay (`ClockGuard.localJumpMs`/`jumpIsTampering`, anchored only at relay-verified moments, and a
jump from an unverified anchor counts only with automatic time off, so network time fixing a clock
never closes a child's apps); the replay baseline remembers which family it belongs to
(`appliedParentTopic`); and the permission cards explain Android 13's "Allow restricted settings".

**Battery and size:** screen time is credited in memory and written once a minute (`UsageBatch`,
about 7 000 commits a day before); the suspension reconciliation asks only about packages whose
wanted state moved, with a full sweep every five minutes (`Enforcer.packagesToCheck`, twelve
thousand binder calls an hour of use before); `DeviceRestrictions.apply` reads what is in force and
writes only the differences; "Restricted" battery use is a critical requirement
(`BACKGROUND_RESTRICTION`); the debug log trims by bytes and caps entries; the two alarm receivers
hold the broadcast open for a bounded eight seconds (`runHeld`); release builds are shrunk by R8 with
app classes kept whole and nothing renamed — **51.5 MB to 8.9 MB**; and new families get child
updates on Wi-Fi only (set in `becomeParent`, not as the field default, which is what old policies
decode to; the parent phone ignores the switch, being the canary).

**Polish and CI:** content keeps clear of the camera cutout; the warning amber is a theme token with
a dark variant; `StringsParityTest` compares placeholders and plural forms; the README is corrected
(rules take about half a minute, one parent phone, resolvers routed, app clones); CI runs Android
Lint against `app/lint-baseline.xml` and refuses coverage below 95% of instructions and 87% of
branches (`jacocoAggregatedVerification`).

**Deliberately not done, with the reason:**

- **D1 (pairing v2, a key per device) and D2 (two parents that converge).** Protocol changes with
  their own cycle and version gates; everything above is local and does not need them.
- **One socket for several families.** A rewrite of the transport with the most delicate history in
  this repo, for a minority of parents, and not verifiable without several real families.
- **The clone-profile restriction** (Samsung Dual Messenger, Xiaomi Dual Apps). Hidden from the
  public SDK; asking for it would put a false "the phone refused this" card in front of the parent.
  Needs checking on those phones.
- **Wrapping the identity DataStore with a Keystore key.** Device transfer is closed by the rules,
  root can use a Keystore key from inside the process anyway, and a failed migration would leave a
  family with no identity.
- **A quiet window for the emergency release**, which would change "twelve hours" into "twelve waking
  hours". And a publish the relay refuses still cancels a release: that contract is deliberate
  ("twelve hours of a phone that can be reached"); what changed is that the parent is told.
- **IPv6 resolver routes** (the tunnel cannot read IPv6) and **the bypass list on by default** (a
  curfewed app already resolves nothing; address literals were the gap, and they are routed).
- **The dependency train** (Room, OkHttp 5, AGP, Kotlin, Compose BOM), **splitting `SyncManager`** and
  **ktlint**. Each wants a device-verified release of its own; the pure decisions this batch needed
  went into `SyncEngine`, where they are tested, and Lint with a baseline covers what ktlint would
  have added of value.

**Emulator notes from this batch:**

- `bmgr` answers "Backup Manager is not activated for user 0" on a Device Owner AVD: the platform
  disables backup for a managed device, so device transfer was never open on a child. The exposure
  was the parent's phone, which is not managed — so the transfer rules could not be observed on
  `walcott-mapview`.
- The harness simulates the PARENT, so none of the parent-side guards above can be exercised there;
  they are covered by `SyncEngineTest`, `ParentTakeoverTest` and `CurrentDeviceTest`.

**Verification:** `./gradlew test` green, Lint against its new baseline, the coverage floor met
(95.4% instructions, 87.8% branches). On `walcott-mapview` (Device Owner, rotated key): the enrollment
checksum hook says `match=true`. The first non-destructive sweep ran 125 scenarios with 4 failures —
the curfew's tunnel and the low-battery word, every one of them right after the change-mode scenario,
which had stopped the enforcement loop and not started it again (see above); the sweep was also cut
by its own time limit before `SystemApp`, `TimeWarning`, `UpdateWindow` and `WebFilter` finished. With
the fix: those seven classes again, 19/19. Destructive suite: 8/8 twice in a row. Release APK: 8.9 MB, v3 with
the lineage, app classes and serializers present in the shrunk DEX; installed over the Device
Owner on the emulator it starts, draws its screens, runs the enforcement service and keeps the
device owner, with nothing in the crash buffer.

## Shipped in v0.111.0 — a phone that keeps changing its mind about which network it is on

ole asked what `../malachi` had done about Wi-Fi/mobile hand-offs and poor Wi-Fi specifically, in
case anything was still outstanding. Four things were, and one of them was not in the filter at all.

**An empty resolver list was adopted, and briefly sent the whole phone to a public resolver.**
`LinkProperties` arrive in stages: the first one for a network the phone is joining routinely carries
no DNS servers yet. `adoptUpstreams` had a guard for "these are only our own sentinel" but it
required a non-empty list, so an empty one fell through to `DnsUpstreams.choose(emptyList())`, which
is the last resort — replacing the resolvers of the network being left, and clearing the remembered
good one, on *every single hand-off*. On a school or office network that blocks outbound 53 that is a
phone with no DNS at all; everywhere else it loses the names only the local resolver knows.

The rule is now `DnsUpstreams.worthAdopting`, pure and tested, and it goes one step further than
malachi's: the emptiness rule has an opt-out for the single caller that has earned it. A list arriving
from a callback is a network still describing itself, so empty means "not yet". A list re-read by
`recheckResolvers` — which runs only because every resolver in hand has already failed — means "this
network really offers none", and the fallback is the right answer. Same predicate, one flag, and
neither caller has to know what the other assumes.

**A lookup in flight when the network changed spent its whole budget asking resolvers that had
gone** — up to three at two seconds each, and the client's own retry, which would have used the new
ones, delayed by exactly that. On a weak Wi-Fi that hands over repeatedly that is most of the time
the phone feels broken. A `networkGeneration` counter is bumped whenever the resolvers really change,
snapshotted per lookup, and compared between attempts: not before the first, because a change that
arrived before the list was read costs nothing, but after one attempt everything left in that list
belongs to a network the phone no longer has. The answer is SERVFAIL rather than malachi's silent
drop, for the reason written all over this file: the app then asks again at once, against the
resolvers that work.

**`onLost` waited for the new network to introduce itself.** Keeping the old resolvers there is
deliberate and stays — a query arriving between networks is better served by the last known resolver
than by nothing — but the platform announces a new default before the old one has finished
disappearing, so by the time `onLost` runs there usually is one. Taking it there makes the gap one
callback long instead of however long the new network takes to get round to its link properties.

**And the one outside the filter.** `SyncManager.watchNetwork` brings a pending relay reconnect
forward the moment the phone has a network again, and it was watching the DEFAULT network — which,
on a child running the web filter, is Walcott's own tunnel, whose `Network` object does not change
when the thing underneath it does. So the one event it exists for never arrived. This is the exact
bug the filter's own callback was rewritten for in 0.109, in a second place, with no symptom loud
enough to find it: the four-minute WebSocket ping notices a dead socket within about eight minutes
and the half-hourly heartbeat rebuilds whatever that misses, so nothing ever looked broken. The cost
was up to eight minutes per hand-off in which rules, granted minutes and every remote command
stopped arriving while the child still looked perfectly healthy to the parent. It asks for a network
that is not a VPN now, and on its own thread rather than the main looper, because its body opens a
socket.

**Looked at and left.** The pooled HTTP connections are not evicted on a network change, so the
first blocklist download or update check after a hand-off can fail once; the WebSocket is handled
separately and the updater retries, so the visible cost is a line on the lists screen until the next
refresh. The log still does not name the interface or how long ago the resolvers were adopted, which
is the line that let malachi diagnose an eleven-hour outage at a glance. `onCapabilitiesChanged` is
not observed: it matters there because a captive portal decides which resolvers that app may use at
all, and walcott always prefers the network's own.

## Shipped in v0.110.0 — the second pass over the filter, and a number nobody had checked

ole asked for the review of `../malachi` to continue, pointing at its changelog for August in
particular. That turned out to be the right month: its whole network path was rewritten there, over
about thirty commits, each naming the phone it broke on. Three agents read them. Most of what came
back walcott already had, or is structurally immune to — it advertises a sentinel address rather
than taking over the router's, it has no user-chosen resolver to be wrong behind a captive portal,
and as Device Owner it puts a strict Private DNS back to automatic rather than working around it.
What follows is what it did not have.

**The count a parent reads was two to three times the truth.** Android's resolver issues A and AAAA
**in parallel** for every `getaddrinfo`, and a browser adds HTTPS beside them, so one tap on one
link arrives at the tunnel as two or three questions for the same name — and every one was counted,
both in the persisted "blocked today" totals and in the domain viewer, whose `Sighting.count` KDoc
already promised "how many lookups, not how many packets" and had never delivered it. `LookupBursts`
now groups them: same name, same app, within two seconds, one lookup **until a record type
repeats**. The repeat is the discriminator — no resolver asks for A twice in one breath — so a
client genuinely retrying still counts, which a plain "ignore duplicates" would have thrown away,
and a domain something keeps hammering is exactly what a parent wants to see. Sixteen slots, three
primitive arrays, no allocation: this is reached once per DNS query. The decision is made once, in
the packet loop, and handed to both counters, because two answers to the same question would
disagree by a factor of two.

**"It stays blocked until you classify it"** was what a parent was told when a new app appeared on a
child's phone, and it had stopped being true some releases ago. There are no categories to classify
into; an app with no assignment reaches `RuleEngine.evaluate` and comes out ALLOWED, subject to
bedtime, the screen-free windows, the pause and the day's total like everything else, and limited
only if the family set a default per-app budget. The one thing that does block a new app is the
install guard, which has its own louder notification with the two buttons that answer it — and this
notice explicitly excludes the packages that guard has quarantined, so it fired precisely when the
app was working. It now says the app has no limit of its own yet. Four comments asserting the same
falsehood went with it: they are why the strings stayed wrong, because the code said in prose what
nobody checked against the engine.

**An answer too big for one datagram never resolved.** `DatagramSocket.receive` discards whatever
does not fit the buffer — no error, no flag — and `DatagramPacket.getLength()` then reports the
BUFFER's length rather than the datagram's, so a cut answer is indistinguishable from one that just
fits. 0.109 refused those, honestly but uselessly: the name failed with the filter on and worked
with it off, which for a DNSSEC-signed zone or a long TXT record is most of them. Now the cut
becomes an honest truncation (`DnsMessage.truncated`: TC set, all four counts zeroed, the question
kept only when it can be walked) and the filter **asks the same resolver again over TCP itself**,
length-prefixed as RFC 1035 §4.2.2 wants, and relays the whole answer if it fits the tunnel. Two
details that are the difference between that working and silently doing nothing: the two-byte length
prefix is read with `readFully`, because TCP is entitled to deliver one byte of it and treating that
as failure is invisible under load; and every timeout is floored at a millisecond, because in Java
`soTimeout = 0` does not mean "no time left", it means block for ever.

**A blocklist could take the phone's own connectivity check away.** Android decides a Wi-Fi has
internet by fetching a `generate_204`; a list that refuses that hostname makes the phone mark a
working Wi-Fi as dead and — on every vendor with "adaptive connectivity" — leave it for mobile data,
on the family's allowance, with nothing anywhere saying why and this app reporting the filter as
healthy. `NEVER_BLOCK` already spared `gstatic.com` and so Android's main probe, but not
`connectivitycheck.android.com`, `clients3`/`clients4.google.com`, `www.google.com`, or Xiaomi's,
Huawei's and vivo's — which are the ones that actually get blocked, appearing on aggressive lists as
telemetry. They are spared as whole hosts, never as a registrable domain (sparing `google.com` would
spare `dns.google.com`, which is what the bypass list exists to block), and the guard sits below
what the family typed and above what a list decided: a parent who blocks a probe by hand still
blocks it, and so does a per-app rule and the curfew.

**Every UDP datagram reaching the tunnel was read as a DNS query, whatever port it was addressed
to.** So a QUIC or HTTP/3 handshake aimed at the sentinel had its bytes walked as a question and
then forwarded to a real resolver on port 53. Now the port is read, and anything that is not 53 is
refused with an ICMP port-unreachable rather than dropped — the twin of the TCP reset 0.109 added,
and for the same reason: a connectionless protocol cannot tell silence from a slow network, so the
asker waited out its whole timeout before trying anything else.

**Smaller, each with its own symptom:** on Android 10 and 11 the network callback reports every
matching network, so the filter adopted whichever spoke last — a phone holding Wi-Fi and mobile data
could ask the mobile resolvers while browsing over Wi-Fi; the candidates are ranked now
(`UnderlyingNetworks`, validated first, then wire, Wi-Fi, mobile), and validation decides which to
prefer rather than whether to have an answer at all. `setUnderlyingNetworks` could name a network
the phone had left, because it was only ever reached from a successful adoption; `null` means "follow
the system default" and is now declared whenever nothing real can be named, with "declared nothing
yet" tracked apart from "declared null". Both callbacks moved off the main looper onto their own
`HandlerThread` — half a dozen binder round trips several times a minute on the UI thread of the
process a child's screens are drawn from. Every callback body is wrapped: a `NetworkCallback` has no
exception barrier of its own, and a throwable out of one takes the enforcement process down while
the app goes on saying it is on. One lock around the adoption, because the fields are volatile
individually and inconsistent as a set. The re-read after a total failure is throttled with a
compare-and-set, since sixteen forwarders fail in the same millisecond. A reset for an already
acknowledged segment now carries the sequence the sender expected, or a peer that checks ignores it
and goes back to waiting. A forged answer no longer claims a question it could not walk. And the
last-resort resolver comes in both families, because one IPv4 literal left an IPv6-only mobile
network with nothing reachable at all.

**Read and deliberately not taken.** Malachi pins its upstream sockets to the network whose
resolvers they are asking (`Network.bindSocket`) — it tried that, it returned `EPERM` and broke DNS
outright, it was reverted, and it was re-landed a day later once the real cause turned out to be a
dead network reference rather than the call. Walcott does not pin, so it has neither the bug nor the
benefit; the benefit is real only in the seconds around a hand-off. Its ICMP echo relay through an
unprivileged ping socket is for addresses its bypass guard routes, and walcott routes only its own
sentinel, which it already answers itself. Its IPv6 sentinel and tunnel remain the one substantial
thing walcott lacks, and the README says so.

## Shipped in v0.109.0 — the web filter, read against a filter that has been doing this longer

ole asked for a review of the web filter "based on the lessons learned in ../malachi" — a
sibling project of his that is a dedicated DNS ad blocker: ~2500 lines of VPN service, a pure
`IpPacket` with 545 lines of tests, and instrumented tests that turn Wi-Fi off and back on.
Walcott's filter was 430 lines with no test of a single packet. Eighteen lessons came back; what
follows is what was taken, and what was deliberately left.

**Two pure modules where there was none.** `DnsMessage` and `IpPackets` now hold everything that
can be decided from bytes, with 52 tests. Before this, the only way to find out that a reply was
malformed was to watch an app fail to use it.

**The three things that were quietly wrong:**

- **Any datagram that arrived was relayed as the answer.** The upstream socket was unconnected
  and the transaction id was never checked, so the first reply to reach the phone's ephemeral
  port won — anybody on the same Wi-Fi could beat the real resolver to it and put an address of
  their choosing in front of the child, for a domain the family had blocked. The socket is
  connected now and the id is checked.
- **The forged reply echoed the query's header.** NXDOMAIN and SERVFAIL were made by flipping
  two bits in a copy of the whole query, so the counts still said "one question and one
  additional record" — the EDNS OPT every resolver sends — in a message that then contained
  neither. A strict stub resolver discards that, and the block appears not to have happened.
  The reply is rebuilt: question kept, everything after it dropped, all four counts written.
- **A resolver that answered "no" ended the lookup.** SERVFAIL and REFUSED were relayed as
  answers, so a router handing out a resolver that refuses everything left the phone resolving
  nothing with the filter on and everything with it off. Those are now "did not answer": the
  next resolver is tried, the refusal kept only if nobody does better — and the resolver that
  did answer is remembered, so a merely slow first one stops costing its timeout on every lookup.

**The read loop was spinning a core.** `establish()` hands back a NON-BLOCKING descriptor, so
`read()` returned 0 immediately whenever no packet was waiting and `if (length == 0) continue`
went round again — flat out, on an idle phone with the screen off. It parks in `poll()` now and
is woken by a pipe when it should stop. (Walcott had already met the other half of this, the
`length < 0` spin, in an earlier release; this is the same bug's twin.)

**The descriptor was closed out from under the writers.** A stop closed the pfd immediately while
up to sixteen forwarders could be inside a write. A file descriptor number is free the moment it
closes and the kernel reissues it to the next thing the process opens, so a straggler could write
a DNS response into a DataStore file or the sync socket. Now: stop, wake the reader, take the
descriptor away from the writers under the lock, join the reader, then close.

**A revocation was permanent until something else happened.** `onRevoke` called `super`, whose
implementation is `stopSelf()`, and nothing retried — so another VPN app connecting for ten
seconds left the filter off until the watchdog next ran, up to a quarter of an hour of unfiltered
browsing with nothing saying so. It backs off and comes back now (5 s doubling to 5 min).

**The callback stopped describing the network and started describing us.** It followed the
DEFAULT network, which becomes our own tunnel once it is up; its "DNS servers" are then the
sentinel, which is excluded from the upstream list — so the filter would have quietly sent every
lookup on the phone to the public fallback, and on a school Wi-Fi that blocks outbound 53, to
nowhere. It now asks for a network that is explicitly NOT a VPN, declares that network as the
one underneath, and re-reads the resolvers whenever every one of them has failed.

**Smaller, each with its own symptom:** the tunnel says it is not metered (it carried DNS and
made the phone believe it was on mobile data — which, among other things, made the blocklist
refresh's "unmetered only" constraint permanently unsatisfiable); it declares an MTU and refuses,
loudly, an answer that will not fit; a TCP connection to the resolver gets a reset instead of
silence, so a resolver falling back to TCP fails in milliseconds rather than a minute; a ping to
the resolver is answered, because a connectivity check that pings its own DNS server and hears
nothing concludes the network is dead; "Block connections without VPN" is noticed and reported;
IPv6 resolvers are used (refusing them left a v6-only carrier with no working DNS), link-local
ones are not (without the zone id they are a guaranteed timeout); a fragment is refused rather
than forwarded as if it were a whole query; a message that is not a standard query no longer has
its bytes read as a hostname and shown to a parent as "this app looked up…"; and the app that
asked is only looked up when some rule actually depends on it, which on a family with no per-app
rules is never.

**Taken from malachi and then rejected:** `allowBypass()`. It is right for an ad blocker, where
letting an app opt out of the tunnel is a courtesy, and wrong here, where it is a way round the
rules. The cost is that an app binding a socket to a specific network (Android Auto, some cast
SDKs) is refused; that is the trade a parental control should make.

**Left undone, on purpose and written down:** the tunnel is still IPv4 (a phone with no IPv4 at
all is unsupported); there is still no TCP DNS fallback for a truncated answer, only the reset
that makes it fail fast; blocking still answers NXDOMAIN rather than `0.0.0.0`, which is a
product decision about what a blocked app should experience and not a bug; and the descriptor
lifecycle now deserves the instrumented test malachi has and walcott does not.

## Shipped in v0.108.0 — the secrets a family types

ole asked for a pass over "the dialogs and inputs for passwords and PINs, especially the ones for
setting them up the first time, and make them prettier and friendlier". There were eleven such
surfaces and every one of them was a hand-rolled `OutlinedTextField` with dots behind it — which
tells the person typing nothing: not how many digits are expected, not how many they have got,
not whether the thing they typed twice agrees with itself, and with no way to check what they
actually typed.

Two shared components now (`ui/components/SecretFields.kt`), used by all eleven:

- **`PinEntryField`** — one box per digit, the shape every phone's own lock screen uses. It is a
  real `BasicTextField` with its decoration replaced, so the keyboard, pasting a code somebody
  sent and TalkBack all still work. The boxes are sized against the room there actually is
  (`BoxWithConstraints`), because six boxes and an eye inside an `AlertDialog` came out as tall
  thin slivers and six across a whole screen as billboards. A filled box is tinted as well as
  dotted, so progress reads at arm's length. `slots` is a MINIMUM: a seventh and eighth box
  appear for a longer PIN rather than a fixed six quietly refusing one the app accepts.
- **`PassphraseField`** — an eye, and an honest answer to "is that long enough yet": a bar that
  fills and a line that counts down ("3 characters to go") instead of a complaint once you have
  finished. A repeat field ticks the moment the two agree, rather than waiting for a button to
  say they do not. The eye matters most here: a backup passphrase has no reset, and nobody can
  proof-read twelve characters of dots.

**Creating the family PIN is two steps now** (`PinSetup`, pure and unit-tested): choose it, then
type it again from memory. A second box you can read the first one into confirms nothing, which
is why every lock screen on earth asks this way. A disagreement throws BOTH halves away — either
could have been the typo and nobody knows which — says so kindly, and starts over.

**Three smaller things that were each their own small cruelty.** A refused PIN used to leave six
digits in the field for you to delete before trying again; it clears itself and takes the
keyboard back. A wrong entry shakes and buzzes once, which is what a lock refusing feels like.
And the six digits of a rescue code now act on the sixth — no button to find afterwards, which
matters because the whole situation that code exists for is a phone nobody can reach.

Checked on the emulator, light and dark, on every surface: first PIN (both steps and the
mismatch), the PIN gate, the app lock (empty, typing, refused, cleared), change PIN, show PIN,
the backup passphrase (short, matching), the on-device copies passphrase, and the child's rescue
code (typing, auto-submit, refusal). 2100 JVM tests.

**Deliberately not changed:** the PIN minimum stays six digits and the passphrase minimum twelve
— this pass was about how they are asked for, not what is accepted.

## Shipped in v0.107.0 — the review before other families

A review asked for one thing: what must be fixed before children the author does not know carry
this app. Three inventories (enforcement and the updater; the sync protocol and its crypto; the
rules, the data and the screens), every finding read back in the code before it counted. The
plan with all of it, ranked, is in the session's plan file; what shipped is below, in the order it
mattered.

**The signing key was public.** `walcott-release.jks` had been committed on purpose, password
and all, for a family beta "with no secrets" — and with other families it stopped being one:
anyone could build an APK a child's phone accepts as an update and inherit Device Owner. Fixed
without a factory reset anywhere: the key was **rotated with APK Signature Scheme v3**.
`signing/walcott.lineage` is the proof, signed once by the original key, that the 2026 key
succeeds it; a phone on the original key takes a rotated build as an update, and from then on
refuses anything signed with the original alone (no `rollback` capability in the lineage —
measured on the emulator: the rotated install keeps Device Owner, and an APK signed with the old
key answers `INSTALL_FAILED_UPDATE_INCOMPATIBLE`). CI signs in a separate step with
`scripts/sign-apk.sh` from the `SIGNING_*` secrets, **v3 only**: neither v1 nor v2 can express a
rotation, so producing them would need the original key at every release, which is the thing no
build may depend on any more. `docs/signing.md` has the whole story and how to rotate again.
`version.json` now carries the APK's `sha256` and the download is bounded and checked — defence
in depth, the signature at install is the gate.

**The release could never take off a lock it set.** `PanicRelease` cleared the reset-password
token in step 3 and asked step 4 to use it; and `LockScreen.register` refused during
`inProgress`, so the token handed to `apply` was always null. Two independent reasons the step
was a guaranteed no-op, and neither test suite looked at the keyguard. A family that set a child's
PIN from a distance, lost the parent phone and served the twelve hours got back a phone nobody
could open. The lock now comes off BEFORE the handback, with the token already registered, and a
lock that would not come off is named in the release report. `LockScreenReleaseScenarioTest`
(destructive) proves it, and skips honestly when the platform will not arm the token.

**A four-digit PIN was the root of trust.** Ten thousand guesses against a lockout the wall clock
alone enforced (three wrong, the date forward, three more), and against the hash every child
carries in its policy. Worse: the nightly on-device copies in `Documents/Walcott/` — the family
key AND the parent's signing key — were sealed with it. Ten thousand candidates through PBKDF2 is
minutes on a graphics card; a million (six digits) is still an afternoon, so a longer PIN does not
mend that. New PINs are six digits (old ones keep working; the PIN card says when one is short),
the lockout holds on the monotonic clock too (with the boot count, so a reboot does not read as
hours of lockout), and the local copies are sealed with a **passphrase the parent chooses**
(`SyncManager.enableLocalBackups`), never with the PIN; PIN-sealed copies stop being written and
are overwritten the moment a passphrase is set. Restoring an old PIN-sealed file still works.

**Rules that were half true:**
- `standDown()` (leaving child mode by PIN) ran the handback without waiting for the enforcement
  loop to die — the race 0.102 closed in `PanicRelease`, open on the other door. Both share
  `EnforcementService.stopAndAwait` now, and the loop leaves by itself when the device stops
  enforcing.
- A rescue code opened only the Device Owner path: the accessibility blocker (the whole
  enforcement on a phone protected without being managed) and the DNS curfew ignored it — a
  child rescued at bedtime had their apps back and a browser that resolved nothing.
- The fail-closed branches returned the managed set whole, phone and contacts included, on a
  phone whose dialer or contacts app is an installed one. The one promise the README makes in
  absolute terms, broken on the one path nobody tested.
- A policy file that would not decode fell back to an EMPTY policy: nothing blocked, and — the
  restriction set being empty — every device restriction actively cleared. The comment that said
  "everything gets blocked" was from the era of categories. The last good policy is kept in
  memory, and a fallback (`PolicySettings.fallback`) refuses to say which restrictions to apply.
- Minutes granted to one app never widened the day's total, so approving a child's request on a
  spent day credited minutes the app could not use. A named grant widens the day by its amount,
  and the request card shows the phone's total beside the app's.

**The channel and its commands:**
- After a key rotation (a legacy family restored from backup) `handleIncoming` kept reading the
  identity captured at connect time, so every later message failed the direct check, went in by
  certificate and counted as a rotation — which is what switches the replay gate off. It reads
  the identity fresh, and only a key that changes is a rotation.
- `RelayServer.normalize` accepted `http://` while the release build refuses cleartext at the
  platform level: a migration to one put parent and children on an address none could open, with
  a week before a second move. Cleartext is accepted only where the build permits it (the debug
  harness), and a migration can now move on from one still in flight.
- Every command has a life: the parent's own queue keeps nothing past `COMMAND_TTL_MS`, so
  `LOST_MODE` and `SET_RELAY` arriving later can only be replays. The child keeps a high-water
  mark per action (`appliedCommandMarks`) beside the bounded id ledger, judges age on the clock
  corrected by the measured skew, and credits a bonus only for today or yesterday.
- A rescue code is bound to the phone it is for (`RescueCode.codeFor(…, deviceId)`): the same
  six digits used to open every sibling's phone in that half hour. Gated on
  `PER_DEVICE_MIN_CHILD_VERSION`; the parent's card picks whose phone and shows the family-wide
  code to a child too old to bind.

**The child, closed:** `DISALLOW_ADD_USER` was cleared on every sync and absent from the
defaults (a guest user is a phone where this app does not exist); it is on by default now, with
`DISALLOW_USER_SWITCH`, and `DISALLOW_DEBUGGING_FEATURES` and `DISALLOW_SAFE_BOOT` are offered
and on by default for a child — the "recovery paths while beta" argument stopped holding once
three release doors and an offline rescue existed. Seeded once more into families that predate
them (`hardeningSeededV2`), never re-imposed on a parent who turns one off. And restrictions are
read back: `DeviceRestrictions.apply` answers what the phone refused, which travels as
`ChildSnapshot.restrictionGaps` to a card on the member's page instead of a switch that is on
and does nothing. Also: the install block lifted around the self-update is backstopped by the
alarm and re-armed under `NonCancellable`; the two service collectors that could die on a throw
are guarded like the loop; the DNS loop bounds its fan-out (16 in flight, SERVFAIL past that,
and for the packets it used to drop silently); `startForeground` says when it is refused and
stops rather than being killed in a loop.

**Polish:** durations read in the phone's language (`DurationUnits`, "20 min" in Spanish);
the child's home resolves labels and aggregates a month of history off the main thread, at most
once a minute; the database opens off the main thread at start-up; the ringer prepares
asynchronously; the parent's poll reads a bounded body; the transport waits on one timer thread
instead of one per retry; the notification log prunes on every write and from the watchdog; a
shared backup does not stay in the cache.

**The harness, twice.** `ScreenBudgetScenarioTest.spendTheDay` seeded past the blanket and
earned extras only, because those were the only ones that widened the day; now a named grant
does too, so it seeds past every extra. And the destructive suite failed on its first scenario
for a reason that had nothing to do with releases: `ScheduleScenarioTest` grants the FIRST
fixture an hour, that hour lives in Room until midnight, and every later scenario expecting a
zero-minute budget on that fixture to bite found an hour of allowance instead — on an AVD that
had never run the destructive suite the same day, so FIRST carried little usage to spend it.
`DeviceScenario.pairFreshFamily` now clears granted extra (`--es mode clear_extra`) at every
pairing. Verified: 133/133 non-destructive (129 in the sweep, the four `ScreenBudget` ones
green on the re-run with the seeding fixed), `e2eReleaseTest` 8/8 twice, and the lineage check
on the emulator by hand.

**Not done, on purpose:** `hhmm` stays 24-hour (a 12-hour clock on the English locale would
change strings the e2e reads); `TimeWindow.lengthMinutes` is still minute-of-day arithmetic
across a DST night; `DomainMonitor.record` still sorts per query (only during a session);
signing the envelope header would break every older child and is a protocol change for its own
release. The pairing QR is still a permanent credential and child→parent messages are still
unsigned — both are design decisions with their own entries in the plan, not fixes.

## Shipped in v0.102.0 — a release that cannot leave a phone half-freed

A review of the one thing that must never fail once strangers' children carry this app: giving
a phone back. The happy path was well proven on the emulator; what was not was the release
under anything going wrong, and two of those were real.

**The enforcement loop was alive during the handback.** `PanicRelease` stopped the service at
step 4, after `DeviceHandback` had unsuspended every package at step 2 — and the loop re-asserts
suspensions every thirty seconds without looking at anything but `isDeviceOwnerApp`, which is
true until the last step. A re-assert landing in between was a suspension for the life of the
install. Now the release raises `PanicRelease.inProgress` first, which every gate that writes
Device Owner state reads (`EnforcementService.start`, `Enforcer`, `DeviceRestrictions.apply`,
`VpnController.apply(true)`, `LockScreen.register`, `LocationPolicy`, `NotificationPolicy`,
`LostMode`), stops the service and WAITS for `EnforcementService.running` to go false, cancels
every policy-applying alarm by name, and only then sweeps.

**The released flag was written after the handback.** A process death during the sweep came back
as an enforcing child — with the parent, who had already received the acknowledgement, having
dropped the row. The flag is now written before anything privileged, keys and topic kept until
the end so the acknowledgement can leave; `finishIfInterrupted` runs the whole release again
whenever `released` meets either "still Device Owner" or "still paired" (the second is new: a
released-and-paired identity would otherwise reconnect and publish to a parent that let it go,
so `connect` refuses it too). The release acknowledgement is published with a receipt, like the
panic notices, and the teardown proceeds whether or not one comes back.

**And it says what it could not undo.** `DeviceHandback.run` asks the system again after the
sweep — suspended, hidden, undeletable packages; own restrictions; the always-on VPN; the reset
token — sweeps once more if anything is left, and answers the list. It rides in
`FamilyIdentity.releaseReport` and the mode screen shows it under "Walcott has left this phone",
with the uninstall button every door now offers. **Decision:** Device Owner is given up even when
something could not be put back. Keeping it would leave the terminal state this feature exists to
avoid, and an `unsuspend` does not fail for a package this app suspended; the report is for the
rare OEM refusal, so that it is read on the phone rather than in a log nobody opens.

Smaller, from the same review: `remoteResultLabel` had no case for `expired`, so a parent whose
child was away for a week read "Failed: expired" in red with nothing to do about it; two strings
in both languages still promised a "24-hour request" two versions after it became twelve.

**The tests, which were the other half of the ask.** The parent PIN typed on the child's phone —
the door a family reaches for first — had no coverage of any kind; `finishIfInterrupted` had
none; the harness never checked Device Owner despite promising to. Now: `PinReleaseScenarioTest`
(wrong PIN reaches the parent and changes nothing; right PIN frees the phone, filter included;
two releases at once are one), `InterruptedReleaseScenarioTest` (a debug hook kills the process
before Device Owner is dropped — `am force-stop` cannot — and the next start finishes it), the
relay dying inside the panic's final pause, an eight-day-old release refused. Every destructive
scenario ends on `assertHandedBack`: nothing suspended or hidden on the whole device, no
always-on VPN, no tunnel, private DNS not pinned, and the app's own "everything came off
cleanly". `DeviceScenario` now skips without Device Owner, `isDeviceOwner()` checks the package,
re-provisioning lives in one place with retries, and `e2eReleaseTest` refuses to pass having run
nothing.

Known and left alone: a strict private-DNS hostname the child had set is put back to automatic
when the filter comes up and is not restored on release (the previous value was never kept);
`KEY_BIOMETRICS` does nothing on any device, because `device_admin.xml` declares only
`force-lock` and `setKeyguardDisabledFeatures` is refused — a switch the parent can flip that
promises what the admin declaration cannot deliver.

## Shipped in v0.76.0 — the close nobody answered

`OutageScenarioTest` had been failing on and off for four releases and every note it accumulated
blamed the harness. It was the product, and the bug is one every phone in the family was living
with.

**OkHttp does not answer a close frame for you.** `NtfyTransport` implemented `onFailure` and
`onClosed` and left `onClosing` at its default no-op. So when a relay shut down *politely* — an
ntfy restart, a proxy retiring an idle socket, anything that says goodbye rather than vanishing —
the child took the frame, said nothing back, and the handshake stopped there: `onClosed` never
fired, and `onClosed` is the only place the reconnect starts. The socket then sat half-shut,
indistinguishable from a healthy one, until the keepalive gave up: **four minutes to the next
ping, four more for the pong that never comes**. Up to eight minutes of a phone receiving no
rules, with nothing on either screen suggesting anything is wrong. The fix is to answer the
close.

**Why it read as flakiness rather than as a bug.** The scenario's window was 240 000 ms —
exactly `Http.IDLE_PING_MINUTES`, a window with nothing in it — and then seven minutes; both sit
UNDER the eight-minute worst case, so whether it passed depended on where in the ping's cycle the
outage happened to fall. Four full runs put the failure on the first scenario, then on neither,
then on both, then on the second, which reads exactly like contention and was not. The window is
now **90 seconds** and the tightness is the assertion: a generous one would pass just as happily
on a child that never noticed anything.

Two things keep it from going quiet again: `MockRelay.stop` answers how many subscribers never
acknowledged the close and the outage helper refuses to go on if any did not, and
`MockRelayTest.stopping tells its subscribers` pins the same property hermetically — one second,
no emulator.

**The backoff advanced twice per failure.** A dying socket calls back through `onFailure` and
then `onClosed`, and each call took its own step up the ladder, so a single death ran 3 s, 12 s,
48 s instead of 3 s, 6 s, 12 s. Harmless until the generation guard landed and only the last
thread survived; after it, a phone that lost the relay took minutes to look again. One pending
reconnect at a time now.

**`childVersion` is a publish counter, not a change counter.** `SyncEngine.mergeChild` keeps the
incoming snapshot when its version is >= the one on file, so two publishes sharing a version are
interchangeable to the parent and whichever lands LAST wins — and the relay replays its backlog
from the `since=` cursor on every reconnect, so the last to land is regularly the older of the
two. The counter used to move only when particular fields changed, and the payload had outgrown
that list: usage, battery and the location trail differ on nearly every publish, and a bonus
landing changed the reported extra minutes without touching it at all. The visible symptom was a
parent's view of a child rewinding — usage going down, a marker jumping back to where the phone
was twenty minutes ago — until the next publish put it right.

**The transport now says what it is doing**: socket open, closing, closed, failed, and how long
until the next attempt. Every conclusion above came out of those five lines; before them the
child's log said nothing at all between pairing and the ping timeout.

## Shipped in v0.72.0 — the member's card, rebuilt

ole's own phone, on his real family: "el diseño es algo apretado… los botones del rayo, del mapa y
de abrir detalle son pequeños y no está claro que sean pulsables". Both halves were true and both
came from the same cause — name, three figures and three bare icons all competing for one row.

The card is now three blocks answering three questions in the order they are asked: **who is this**
(avatar, name, the one line that matters — no phone yet, or a phone gone quiet — and the chevron
that says the card opens their page), **how is their day going** (the three numbers in equal
columns, so they line up between siblings and a long label in the long locale can no longer push
the last one onto a line of its own: "Media 30 días" was wrapping under the icons), and **what can
I do about it** (a rule, then real buttons with words on them: Acciones and Mapa).

Two smaller decisions in it: the status chips are indented to the NAME rather than to the card, so
they read as facts about that person; and a member with no phone yet gets no action bar at all —
the sheet would only have said "not linked", and their card leads to the page with the pairing
code. Checked on the emulator in both themes, with one and two buttons, and with the chips
wrapping.

## Shipped in v0.71.0 — the answers that are not rules, and a notification that says something

A quality-of-life sweep over the two screens a family actually lives in.

**Today's exception (`TodayException`, in `:core-rules`).** The gap was the same in both
directions: a family constantly needs to say "not now" and "just tonight", and the only vocabulary
the app had was a standing rule — edit it, then remember to put it back. Two shapes, both dated so
they cannot outlive themselves:

- **A pause**: everything non-essential closed until an instant the parent picked. Above every
  rule in `RuleEngine.evaluate` (no granted minute answers "come to the table"), its own
  `BlockReason.PAUSED` so the child is told the truth rather than shown a window that does not
  exist, and its own `ActiveBlock.Kind.PAUSED` whose action on the parent's side is the phone back.
- **Tonight's bedtime**, later by N minutes or lifted, keyed to the NIGHT rather than the day
  (`nightOf`): a bedtime crosses midnight, so an exception dropped at 00:00 would hand the small
  hours back to the rule it was lifting — the one hour of the night a child would notice. Kept a
  day past its night for the same reason. Every reader of a bedtime now goes through
  `FamilyConfig.bedtimeAt`, so the engine, the child's home and the parent's "what is stopping
  them" cannot disagree about tonight.

Written per member (`ChildOverrides.todayException`) and deliberately outside `customRuleCount`
and `isEmpty` — an exception is not a customized rule, and counting it would tell a parent they
had personalised something an hour before it expired on its own. Published WITHOUT the coalescing
hold (`SyncManager.markUrgentPolicyEdit`): ten seconds is right for a sitting of rule edits and
wrong for a parent watching to see whether the phone went quiet. Gated on
`SyncEngine.TODAY_EXCEPTION_MIN_CHILD_VERSION` (124) — an older child ignores the unknown field
in silence, so the sheet says so before the tap rather than after.

**The permanent notification now says something** (`StatusLine`/`PhoneStatus`). It is the only
sentence this app shows without being opened, and for four versions it read "Usage rules are
active". It now carries what is left of the app in use, or when the phone opens again — from
values the enforcement loop already computes, re-posted only when the text changes (once a minute
for a countdown printed in minutes). It never announces a limit for an app outside the managed
set: screen time is counted for a wider set than can be suspended, and a countdown to a wall that
never arrives is worse than silence.

**Earned time was invisible.** `ChildUiState.earnedMinutes` had been computed, plumbed through
the view model and rendered nowhere since idle-earn shipped: minutes appeared from nowhere, which
is not a reward. One line on the child's home now says what putting the phone down was worth.

**Everything else in the sweep**, all of it about the two screens people actually use:

- The parent's home leads with the members (each row with a lightning button opening
  `QuickActionsSheet`: minutes, pause, tonight's bedtime, locate) and puts the family's rule
  hub *under* them, where it belongs on a screen read top-down.
- The report filters per child and its app rows open that app's limit — "where did the time go"
  and "give it a limit" are one gesture now.
- The app list sorts by most used, with the week's time beside each limit (`UsageLedger.mergeAcross`,
  shared with the report so the two cannot drift).
- The activity wall filters by member and carries day separators.
- `SnackbarController` + `LocalSnackbar`: deleting a special day, a domain or a schedule is
  undoable from the message that reports it. The app had no snackbar at all before this — every
  action answered with a toast, which has no handle and no memory.

**A bug the unit tests could not have found, caught on the emulator.** Lifting tonight's bedtime
AFTER midnight made the row for putting it back disappear: the sheet asked `bedtimeAt` which night
it was in, that answers null on a night already lifted, and the fallback is today's date — so it
compared the exception it was holding against tomorrow night and concluded it belonged to neither.
`FamilyConfig.scheduledBedtimeAt` is the fix, and the reason it exists: "which night is this" is a
question for the RULE, never for what is left of it.

Tests: 1175 JVM across the four modules (0 failures), of which 31 are new — `TodayExceptionTest`
(15, the regression above among them), `PhoneStatusTest` (9), `TodayExceptionDtoTest` (7) — plus
`PauseScenarioTest` on a real device: a pause applied, given back **by the device's own clock with
nothing else sent**, lifted early by the parent, and a bedtime lifted for one night. The rest of
`:parent-sim:e2eTest` stayed green (83/83 after re-running three scenarios that had timed out while
a second emulator was competing for the machine).

## Shipped in v0.64.0 — the reliability sweep, and the phone nobody was telling

A pass over "can this app leave a managed phone in a state nobody can get out of", plus the two
questions that started it: does removing a child ask first, and does it free their phone.

**The parent's message had no ceiling, and two of its fields never stopped growing.** Every
resolution the parent had ever sent and every bonus it had ever granted rode in *every* snapshot
for the life of the install. The child half has had `SnapshotFit` since v0.22.0 for exactly this;
the parent half had nothing. The failure is worse there and completely silent: past ntfy's 4 kB the
publish is refused with HTTP 413 on every attempt and every re-emit, so **no rule change reaches any
child again, ever**, and every child goes on enforcing whatever it last received. New `ParentFit`:
answers stop travelling once nothing can apply them (a bonus is credited to the day it ARRIVES, so
an old one landing on a phone that was off is minutes nobody granted), the message is measured and
degrades in a fixed order — icons, acks, locates, answers, commands last — and when even the bare
rules do not fit, `SyncState.policyTooLarge` says so on the relay card instead of a debug line
nobody reads. A demanding family (4 members, 30 apps with their own limits, 40 blocked domains,
per-child overrides) measures **2304 of 3800 bytes**; `ParentSnapshotSizeTest` fails at 3000 so the
next feature that grows the policy is caught in CI rather than in somebody's house.

The parent keeps answers for a month locally even though they stop travelling after three days —
its own screens read that list to tell "you answered this" from "nobody ever did".

**Other ways a phone could be left stuck, all fixed:**

- **DataStore corruption was terminal.** No `corruptionHandler` meant every read and write threw
  for ever: on a child the enforcement loop's config read fails on every tick, so whatever was
  suspended when it broke stays suspended and nothing re-evaluates it. Now the file is started
  over, and a *replaced policy file* raises the same flag a bad blob does, so the next parent
  snapshot is adopted whatever its version (a re-emit would have been refused by the replay gate).
- **Standing down left everything armed.** Taking a phone out of child mode stopped the service and
  left every suspended app suspended and every restriction on, with no loop left to undo either.
  `WalcottApplication.standDown()` now gives the apps and the settings back; Device Owner is
  deliberately kept, so re-pairing restores everything without a factory reset.
- **An app that LEFT the managed set stayed suspended.** The reconciliation only ever looked at
  what is managed now, and nothing else unsuspends anything.
- **The child's applied-id ledgers grew for ever** (`appliedResolutionIds`, `appliedBonusIds`,
  `appliedCommandIds`) — thousands of UUIDs re-serialized on every check-in. Bounded to 200, which
  outlives everything the parent can still be carrying.
- **A released phone kept its cached blocklists.** Half a million domains under `files/` is not
  personal data, but "nothing left to suggest the phone was ever enrolled" was not true.

**Removing a child asked first, and then told the phone nothing.** It kept applying the family's
rules for ever, appeared under "Other linked devices" described as an *old app version*, could not
be re-linked from its own screen (the pairing scanner only exists while UNPAIRED), and there was no
remote release at all — the only way out was the parent PIN typed on the device itself. Now:

- `RemoteAction.RELEASE_DEVICE`, signed and TTL'd like the lock-screen PIN, running the same
  `PanicRelease` the two existing doors run. The child **acknowledges before tearing itself down** —
  a moment later there is no channel to answer on — and the parent drops the device row when that
  ack lands, so a freed phone never ages into a "not heard from" alert.
- The remove dialog offers it as a choice rather than a default: a phone left limited can be freed
  tomorrow, a phone freed by accident needs a factory reset to re-enrol. Also offered on its own
  (*Free this phone*, beside the other remote fixes) and on an orphaned device.
- An orphan can be **taken back into the family** under the childId it is already enrolled with —
  the recovery from a mis-tap that used to cost a factory reset.
- Gated on `RELEASE_MIN_CHILD_VERSION`: a child too old to understand the command is said so up
  front, not through an "unsupported" ack afterwards.

**Backup recovery, tested end to end rather than by construction.** `RestoreRecoveryTest` plays the
whole story at protocol level for both shapes of family (software key + version leap; legacy
Keystore key + `RotationCert`), including the two silent failure modes — a restored parent whose
snapshots do not verify, and one whose rules lose the version comparison. `RestoredParentScenarioTest`
proves the same against a **real child device**: rules flow again, a key the phone has never seen is
adopted on the strength of the certificate at a LOWER version than it has already applied, and the
restored parent hears the child. `ReleaseScenarioTest` (its own `:parent-sim:e2eReleaseTest` task —
it gives up Device Owner) proves the OS actually lets go.

### The AVD's usage access was denied, and it read as a product bug

`RuleEventScenarioTest` failed twice with "the child never reported the expected state" — on the
0.63.0 build too, so not a regression. `appops get dev.walcott GET_USAGE_STATS` said **deny**: with
any budget in the policy the child correctly fails CLOSED, which means no budget ever runs out and
no rule event is ever emitted. `DeviceScenario` now grants it at pairing time (`ensureUsageAccess`)
and checks it, next to the screen-awake fix from 0.63.0 and for the same reason: a red suite must
mean the product is wrong.

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

One from the 0.100.0 work, and it is not the emulator at all — it is the fixtures:

- **`ScheduleScenarioTest.settleAllowed()` installs `Fixture.FIRST`.** A scenario that adds "a
  second app" and reaches for `Fixture.FIRST` gets the SAME package, and then asks it to do two
  contradictory things at once. It cost two device runs here: the allow-list scenario permitted
  an app and then waited for that same app to close itself. Use `Fixture.SECOND` for the second
  app, and note that the control assertion beside the silence is what made it visible at all.

### The e2e cannot take the network away from the child, and it matters

Found chasing the 0.101.0 bug, and worth knowing before the next person tries: **there is no way
to make the child's relay unreachable in this harness.** The device reaches `MockRelay` over
`adb reverse` on 127.0.0.1, which travels the adb transport rather than the guest's network
stack — deliberately, because that stack vanishes under long runs (see `MockRelay.loopbackUrl`).
So `cmd connectivity airplane-mode enable` cuts the phone off from everything EXCEPT the relay,
and a scenario that uses it proves nothing. It looks like it works: the countdown runs, the
notices land, and if it is the emergency release the phone releases itself and the AVD comes out
the other side no longer Device Owner.

Pulling the reverse out does not work either, and that is already written down in
`MockRelay.refusePublishes`: OkHttp keeps the pooled connection and the open route goes on
serving publishes as if nothing had happened.

What the harness CAN express is every failure the relay itself can answer with, and there are now
three: `refusePublishes()` (503 — told), `dropNext` (200, taken and discarded) and
`answerLikeCaptivePortal()` (200 with a body that is not this relay's, stored nowhere). The third
was added for 0.101.0 and immediately caught a real bug, which is the argument for it.

### Still open: `TimeWarningScenarioTest` and the three causes that are NOT it

Left unfixed at 0.100.0 deliberately, and written down so it is not diagnosed a fourth time. The
class fails intermittently and **which of its scenarios fails changes every sweep**: full sweep
one was 131/131; full sweep two failed `bedtime is announced once`; three isolated runs straight
after failed `an app close to its limit`, the one both sweeps had passed. The same APK was
installed throughout, so nothing in 0.100.0 causes it.

The assertion that fails is always `assertShown`'s `mIsInterruptive` — the PLATFORM's record that
it put the banner on screen. In every failure the product had posted the right warning with the
right title; only Android's verdict on whether it peeked differed. All three explanations this
repo has previously reached for were measured on the AVD that day and are **ruled out**:

- heads-up snooze — `heads_up_snooze_length_ms` is `0` and SystemUI's dump agrees
  (`mSnoozeLengthMs=0`, `snoozed packages: 0`). The `ChildDevice.keepAwake` fix works.
- a demoted channel — `walcott_time_warning` is `mImportance=4`, `mDemoted=false`, and other
  records on that channel in the same dump are `mIsInterruptive=true`.
- a sleeping screen — 90 seconds untouched and `mWakefulness=Awake` throughout. The comment in
  `keepAwake` about dozing within a minute despite a 24-hour timeout no longer describes this AVD.

The lead worth trying next, unconfirmed: `bedtime is announced once` separates its two rungs by
`RUNG_GAP_MS` (60 s) and `an app close to its limit` separates its two by **nothing at all** — so
its second rung lands on a heads-up that may still be on screen, and Android does not peek what is
already peeking. The rungs a child actually meets are 30, 5 and 1 minutes apart, so the
compression belongs to the scenario and not to the product. Do NOT loosen `assertShown` to make
this green: a generous assertion passes just as happily on a phone that never warned anybody.

One from the 0.99.0 work, and it is about the AVD's own memory rather than the platform:

- **Extra time granted by an earlier scenario is still there.** Bonus minutes live in Room until
  midnight, and a blanket grant (`__all_apps__`, and now `__earned__`) widens the DAY as well as
  an app's budget — so a scenario that sets a daily total "with forty minutes of headroom" and
  then seeds forty minutes of usage does NOT necessarily spend it: `GrantScenarioTest` alone
  leaves thirty-five minutes lying around. The first draft of `ScreenBudgetScenarioTest` read
  that as a product that had stopped enforcing, and worse, its "never limit" case passed while
  the day had never been spent at all. Seed past the extra as well as the headroom, and put a
  control in any test whose assertion is a SILENCE.

Four from the 0.98.0 work, all of them platform facts rather than emulator quirks — measured on
the AVD and worth not re-deriving:

- **SystemUI snoozes heads-ups PER PACKAGE for a minute** after one of that package's heads-ups
  goes away: `adb shell dumpsys activity service com.android.systemui/.SystemUIService` prints
  `HeadsUpManagerPhone state: ... mSnoozeLengthMs=60000`. While it runs, the next notification
  from that package is filed in the shade with `mIsInterruptive=false` — the product posted the
  right thing and the platform declined to show it. This is what made `TimeWarningScenarioTest`
  fail intermittently in full sweeps and pass alone, twice diagnosed as "load" before it was
  measured. Turn it off with `settings put global heads_up_snooze_length_ms 0` (note the key:
  `heads_up_notification_snooze` is a DIFFERENT setting and does nothing here). It takes effect
  live — SystemUI has an observer on it — and the dump above is how you check it took.

- **A device owner CAN suspend most preinstalled apps, and the platform itself refuses the
  dangerous ones.** `pm suspend` (which shares `canSuspendPackageForUser` with the DPM path)
  accepted Chrome, YouTube, Photos, Camera, Clock and even Play Store, and refused the default
  home, the default dialer, Settings and the permission controller. It also accepted the
  KEYBOARD and SystemUI, which is why Walcott keeps a denylist of its own: neither has a
  launcher icon, so neither reaches the list a parent reads, but a phone with no keyboard cannot
  type its own unlock PIN.
- **A suspension by a device owner is attributed to the PLATFORM, not to the admin app.**
  `dumpsys package <pkg>` shows `Suspend params: suspendingPackage=<0>android dialogInfo=null`,
  so tapping the app opens `com.android.settings/...ActionDisabledByAdminDialog` ("Blocked by
  work policy") rather than the `SuspendedAppActivity` that would have named Walcott. Two
  consequences, both dead ends worth writing down: `SuspendDialogInfo` needs `SUSPEND_APPS`,
  which a device owner does not hold, and the "More details" button `SuspendedAppActivity` can
  offer resolves against the SUSPENDING package — the platform — so no activity of ours can ever
  be behind it.
- **`setShortSupportMessage` reaches the restriction dialogs but NOT the suspended-app one.**
  With the message set, Settings ▸ Date & time ▸ "Set time automatically" shows it in place of
  "For more info, contact your IT admin"; the blocked-app dialog keeps the generic line. Also
  note the message is not readable from adb (`dumpsys device_policy` does not print it, like
  `setDeviceOwnerLockScreenInfo`) — it is asserted from inside the app in
  `PolicyEnforcementDeviceTest`.

Four from the 0.97.0 work, all of which made a working feature look broken:

- **An ongoing notification mints an auto-group SUMMARY on its own channel, and the summary
  outlives it.** Cancelling the ring's notification (id 7001) leaves a record with
  `id=2147483647 tag=ranker_group ... GROUP_SUMMARY` on `channel=walcott_ring`, ongoing and
  uncleared. So "is anything of ours posted on that channel?" answers yes for ever, and a scenario
  written that way reads a ring that stopped as a ring that never did. Match the record's own
  flags (`!contains("GROUP_SUMMARY")`), or assert on the effect instead — the ring scenario now
  waits on the alarm stream's volume going back where it was, which is the audio service's own
  record that the ring is over.
- **`dumpsys battery set level N` does not move `BatteryManager.BATTERY_PROPERTY_CAPACITY`.** It
  moves the sticky `ACTION_BATTERY_CHANGED` (and `dumpsys battery` agrees with itself), while the
  HAL property the app reads went on answering 100 — so a last word said "at 100%" while the phone
  was being told it was dying. The fix is right on a real phone too: the level for a last word is
  read from the sticky broadcast, which is the one that carries the level that crossed the mark.
- **`cmd media_session volume --stream 4 --set 2` reports success and changes nothing.** It prints
  "will set volume to index=2" and "Connecting to AudioService", and `dumpsys audio` shows the
  stream exactly where it was. There is no `media` binary on this image either. Setting a known
  volume needs a debug hook inside the app (`--ei alarm_volume N`).
- **A phone with no lock screen cannot be seen to lock.** `locksettings set-disabled true` is how
  earlier scenarios leave the AVD, and with it `lockNow()` blanks the screen and comes straight
  back to the launcher — nothing shows in `dumpsys window policy`. Turn the swipe keyguard on for
  the scenario (`locksettings set-disabled false`) and put it back in the `finally`. Two traps
  behind that one: `set-disabled` is a **no-op while a credential is set** (it only moves between
  Swipe and None), so a scenario that leaves a PIN behind leaves the keyguard behind too; and
  "is it locked?" has to be asserted as a **transition** (unlocked, then locked), because a
  keyguard shows whenever a screen times out and an already-locked phone would satisfy it without
  the command doing anything.
- **The line lost mode writes on the lock screen cannot be read back from adb.**
  `setDeviceOwnerLockScreenInfo` does not go to `Settings.Secure.device_owner_info` on this image
  (it answers null), does not appear in `dumpsys device_policy`, and `locksettings` has no getter.
  It really is written — with lost mode on, the text turns up in `/data/system/locksettings.db`
  under `adb root` — but rooting to assert it breaks `cmd notification post` for everything after,
  so the scenario asserts the lock instead and says why.

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
