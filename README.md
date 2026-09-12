# Walcott — family parental control for Android

[![CI](https://github.com/olemoudi/walcott/actions/workflows/ci.yml/badge.svg)](https://github.com/olemoudi/walcott/actions/workflows/ci.yml)
[![coverage](.github/badges/coverage.svg)](https://github.com/olemoudi/walcott/actions/workflows/ci.yml)

Screen-time rules for your kids' phones that actually hold: no accounts, no subscriptions,
no company holding your family's data. Everything runs on your own phones.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/child-status.png" width="170"><br><sub>Child — time left</sub></td>
    <td align="center"><img src="docs/screenshots/child-bedtime.png" width="170"><br><sub>Child — bedtime</sub></td>
    <td align="center"><img src="docs/screenshots/parent-mode.png" width="170"><br><sub>Parent mode</sub></td>
    <td align="center"><img src="docs/screenshots/limits.png" width="170"><br><sub>Limits &amp; bedtime</sub></td>
    <td align="center"><img src="docs/screenshots/pairing.png" width="170"><br><sub>Set up a child phone</sub></td>
  </tr>
</table>

## Download

Point your phone's camera at this code, or tap the link below.

<img src="docs/install-qr.png" width="200" alt="QR code linking to the latest Walcott APK">

**[github.com/olemoudi/walcott/releases/latest/download/walcott.apk](https://github.com/olemoudi/walcott/releases/latest/download/walcott.apk)**

The same app is both the parent app and the child app — you choose which on first launch.
Android 10 or newer. Once installed, Walcott keeps itself up to date from this same page.

Your phone will warn you that the file comes from outside the Play Store, and Play Protect
may offer to scan it first — that is normal for any app installed this way.

> **Beta software.** Walcott is used by its author's family every day and is offered as-is.
> The child's phone has to be set up from scratch, so try it on a phone you can afford to
> factory-reset.

## What it does

**Time limits that understand the week.** Any app can have its own daily limit, and each limit
is per kind of day: school days, weekends, and special days. Bedtime is per kind of day too.
There are no categories to sort apps into — a newly installed app simply has no limit until you
give it one.

**A limit for the phone itself.** Optionally, a total screen time per day — one number for the
whole phone, per kind of day. Every minute spends it, whatever it was spent on, including apps
you have never given a limit to; when it runs out the phone closes for the day. It is the
ceiling the per-app limits do not add up to on their own: ten apps with an hour each is a
ten-hour day. Calls and contacts keep working, and so does anything marked "never limit this" —
that one is a family's own way out and a ceiling must not close it. Each member can have a
shorter day than the family's.

The apps a child reaches a person with are outside both of the limits above — the phone and
contacts, which answer to no rule at all, and the messaging app, which the family default and
the daily total leave alone. A limit that stops a child texting their parent should be one
somebody chose, so it can only be set on that app by name, and the screen that sets it says what
it will do.

**An optional limit for every app.** If you want one, set a daily limit that every app gets
unless you have given it its own. Each app spends it on its own clock: an hour of one doesn't
eat another's. It is off by default, and only the most detailed guided setup asks about it.

**Special days.** Mark a holiday, a school break, or a birthday, and the rules for that day
change automatically. A special day can apply to the whole family or to one child only — a
birthday belongs to whoever's it is.

**Screen-free windows.** Block every app during homework, dinner or class, family-wide or per
app, on the days you choose — and name the apps that stay open, because "homework, five to
seven" is almost never "nothing at all". The child's phone says which those are, so they can see
what they are still allowed.

**Asking for more.** The child can request extra time from their phone; you get a
notification and approve or deny with one tap. You can also hand out bonus time unprompted.

**"Not now", and "just tonight".** Some answers are not rules. Every member's row on your home
screen opens a small sheet: give them 15, 30 or 60 minutes, **pause their phone** (dinner is
ready, homework is not done) or **move tonight's bedtime** back — or lift it altogether, for
tonight. Both undo themselves when their time is up, so nothing has to be remembered and no
standing rule is quietly left changed. Calls and contacts keep working throughout a pause, and
the phone says it is paused and until when.

**Earned time.** Optionally, time spent off the phone during set hours converts into extra
screen time for every app — putting it down is worth something, and their own screen says how
much it was worth today.

**Apps that came with the phone.** The browser, the video app, the gallery — usually where the
day actually goes, and normally out of a parental control's reach because a phone that suspends
its own system apps is a phone that stops working. Walcott will limit one if you ask for it by
name: open the app and turn on "Limit it even though it came with the phone", and from then on
it answers to limits and bedtimes like anything else. One app at a time on purpose — there is no
list of built-in apps that is safe on every phone. The phone itself refuses the ones it cannot do
without (the home screen, the dialer, Settings), and if it refuses anything else you are told on
that member's page rather than left wondering.

**Per-app rules.** Any single app can have its own blocked hours as well as its own limit —
and one app can be marked "never limit this", so a bus timetable or a chat with you is always
reachable. **The phone and contacts apps are never limited by anything**, not even at bedtime:
a child has to be able to call, and above all to call you — and a number they can't look up is
a call they can't make.

**Web filtering.** Block specific domains without root, using a local VPN that only inspects
DNS. You can see what a child's app is actually contacting and block it from there.

**Where they are.** Optional location, with a recent trail on your map.

**More than one family.** One parent phone can manage several families — each with its own
children, its own rules and its own private channel, kept completely apart. Add one from
*Switch family*, or take one over from its backup file.

### Also for a phone you help with, not just one you limit

When you add someone you choose whether it is **a child** or **an adult you help** — a parent, a
grandparent, anybody who changes a setting by accident and can't find their way back. The choice
never limits what you can switch on; it decides what that phone *starts* with and what the app asks
you next. An adult's phone starts with no limits, no bedtime and no location, and instead locks the
settings people change by accident: airplane mode, the language, brightness, screen timeout, mobile
data, resetting the network, accounts, default apps, and installing or removing apps. Their own
screen is a single page with one big **Ask for help** button, which reaches you as an alert.

Three support tools are offered for *every* phone in the family, child or adult — a teenager's phone
on silent for two days is the same problem as a grandparent's:

- **Keep the ringer audible.** Silent and vibrate are undone and the ring volume kept up, and you
  are told how many times that has been necessary. From your end, a phone on silent is
  indistinguishable from one that is off — and its owner has no idea.
- **The lock screen.** Change or remove their unlock PIN from your phone, or lock theirs now. Their
  page tells you whether that is ready *before* you need it: the phone has to be unlocked once with
  its current PIN first, and finding that out on the day somebody is locked out is too late.
- **A notification log you ask for.** Off by default. Their phone keeps what arrived for 48 hours
  and answers when you ask — everything, or just one app, so "did the message from the clinic
  arrive?" doesn't mean reading a day of somebody's messages. Nothing is ever uploaded on its own,
  and switching it off deletes what was kept.

**Emergency button.** A way out that doesn't depend on you being reachable — see below.

The app is fully usable in **English** and **Spanish**, following your phone's language.

## Setting it up

**On your phone (the parent):** install, open, choose *Parent mode*, set a PIN. Create a
child, and Walcott shows you a QR code to pair with.

**On the child's phone:** Walcott has to be installed on a **new or factory-reset phone** —
that is what lets it hold rules the child can't simply switch off. During the initial Android
setup wizard, tap the welcome screen six times to open the QR reader, and scan the enrollment
code. Then open Walcott, scan the pairing QR from your phone, and you're done.

Rules you change on your phone reach theirs within seconds.

**Worth knowing before you start:**

- The child's phone must be set up from scratch. There's no way to convert a phone already in
  use without wiping it.
- Walcott is **not compatible with Google Family Link** on the same device — pick one.
- Set up a backup (Parent mode → settings) as soon as you have rules worth keeping. It is how
  you recover if your phone is lost or replaced.

## No accounts, no servers of ours

There is no Walcott account and no Walcott server. Your phones talk to each other through a
public notification relay, and everything they say is **encrypted end-to-end** with a key
created when you pair — the relay carries sealed envelopes it cannot read, and rule changes
are signed so only your parent phone can issue them.

Nothing about your family reaches us, because there is no "us" to reach: no analytics, no
crash reporting, no ads, no telemetry of any kind.

## When the rules can't be trusted, they tighten

Two things the rules depend on can be taken away on the child's phone, and both are answered
the same way — by blocking managed apps until they come back, so tampering costs time instead
of buying it:

- **Usage access**, without which budgets never count down.
- **The clock.** Every rule is a rule about *when*, so a clock moved forward would walk past
  bedtime and hand back a fresh day. Walcott measures the phone's clock against the relay's
  timestamps instead of trusting it, and past 15 minutes of drift the rules fail closed. The
  child's home screen says so and points at the setting; with automatic time on it fixes
  itself as soon as there's a network.

A family with no budgets, windows or bedtime can't be cheated this way, so neither rule locks
anything on a phone nobody was limiting.

## Getting a phone back

Walcott is deliberately hard to remove, which raises a fair question: what if the parent's
phone dies and the backup is gone with it?

From your own phone, freeing one you look after is one tap: *Free this phone* on their page, or
the same offer when you remove them from the family. Every restriction comes off, every app comes
back, and the phone drops out of management — if it is switched off, the moment it comes back. It
cannot be undone: enrolling it again means setting it up from scratch, so the app asks first.

And if your phone is the one that is gone, there are two ways out from the child's phone:

- **With the parent PIN** — settings → *Remove Walcott from this device*. Unblocks every app,
  gives back every setting (an unlock PIN you set from your phone included), erases the rules
  and history and drops out of management. Nothing is left to suggest the phone was ever
  enrolled.
- **Without the PIN** — the child can request the same release from their home screen, and it
  takes 12 hours of being *loud*: parents are notified immediately and again every hour,
  each alert carrying a one-tap refusal. The phone must keep reaching the family channel the
  whole time, and the countdown runs on the relay's clock, so moving the phone's clock does
  nothing. A refusal ends the request and blocks new ones for three days.

That second route is a deliberate trade-off: a determined child can free their phone in half a day,
but only by telling their parents a dozen times first — and a factory reset, which Walcott
does not block, was always the faster way out anyway.

## When there is no network at all

A rescue code opens a child's phone with nothing arriving: no data, no Wi-Fi, a phone abroad, or
one that has locked itself down because its clock or its permissions went wrong. The parent's
settings show six digits for the phone you pick; the child types them into their own Walcott,
with no PIN, and the phone opens for an hour, three hours, or long enough to install something.

It is deliberately not the parent's PIN. That PIN releases a device permanently, so spending it
to buy an hour — in front of the person it exists to keep out — is spending the wrong thing, and
a PIN just changed does not work offline until the new policy arrives anyway. The code is derived
from the family key both phones already hold (HMAC-SHA256, truncated the way an authenticator
app does it) over a thirty-minute slot and the phone it is for: nothing is sent, nothing is
enrolled, and no new secret exists. It works once — the phone remembers the newest slot it
accepted — so a code overheard or remembered is worth nothing twice, a clock moved backwards only
reaches slots already spent, and a sibling who hears it has nothing.

## Honest limitations

- **Web filtering is DNS-based.** It catches ordinary domain lookups. Apps that ship their own
  encrypted DNS or hard-coded addresses — notably YouTube and some browsers — can get around
  it. Blocking those properly needs full traffic inspection, which Walcott does not do.
  The phone's own **Private DNS** setting would get around it too, from Settings and in two
  taps, so "Protect the web filter" locks that setting as well and puts a strict private
  resolver back to automatic while the filter is running.
- Per-app attribution of a domain lookup is best-effort; when a lookup can't be attributed,
  "only from this app" rules block rather than allow.
- **The tunnel itself is IPv4**, though it forwards to whichever resolvers the network offers,
  IPv6 ones included. Queries reach it either way, because the phone sends them to the resolver
  this app advertises. What is not supported is a phone that has no IPv4 at all.
- **A phone that only speaks DNS over TCP is not filtered.** Walcott answers a TCP connection to
  its resolver with a refusal rather than silence, so an app fails at once instead of waiting out
  a minute-long timeout, but it does not follow the query.
- Android's own **"Block connections without VPN"** must stay off. Walcott's tunnel carries DNS
  and nothing else, so that setting would leave the phone with no network at all; the app says so
  when it notices it is on.
- **Android decides what a blocked app says.** When a limited app is tapped, the phone shows its
  own "Blocked by work policy" dialog. Walcott cannot change that wording or put a button on it:
  Android attributes an admin's suspension to the platform itself, and the message a suspending
  app can supply needs a permission a device owner is not given. Everywhere else Android names an
  administrator — changing the date, installing something — it now says Walcott is keeping the
  family's rules and to open it to ask for more time.
- Walcott counts foreground app time. It is not a keylogger, a message reader or a screen
  recorder, and it is not intended to become one.

Source is public so anyone can check these claims for themselves.
