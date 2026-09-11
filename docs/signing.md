# Release signing

Walcott updates itself silently on the child's phone, as Device Owner. The **only** thing
standing between a child and an APK of their own making is the platform's rule that an update
must be signed with the same key as the installed app. So the signing key is the root of trust
of the whole product, and it lives **outside this repository**.

## History, and why there is a lineage

Until 0.106 the release key (`walcott-release.jks`, password `walcott`) was committed to this
public repository on purpose — a family beta with "no secrets". That stopped being true the day
the app was shared with other families: anyone could build a Walcott that installs *over* a
child's and inherits Device Owner.

Re-signing with a fresh key would have broken the update chain (every child = factory reset).
Instead the key was **rotated with APK Signature Scheme v3**: `signing/walcott.lineage` is a
proof, signed by the original key, that the 2026 key succeeds it. A phone carrying a build signed
with the original key accepts a build signed with the new key *plus the lineage* as an update,
and from then on refuses anything signed with the original key alone — the original signer has
no `rollback` capability in the lineage. `minSdk 29` is above the API 28 floor for v3, so every
supported device understands it.

The original key remains in git history. That is fine: it can no longer sign an update for any
phone that has taken a rotated build, and it was needed exactly once, to sign the lineage.

## Where the key is

- **CI**: repository secrets `SIGNING_STORE_B64` (the keystore, base64), `SIGNING_STORE_PASSWORD`,
  `SIGNING_KEY_ALIAS` (`walcott`), `SIGNING_KEY_PASSWORD`. `release.yml` decodes it into
  `$RUNNER_TEMP`, builds the release APK **unsigned**, and signs it with `scripts/sign-apk.sh`.
- **ole's machine**: `~/.walcott-signing/walcott-release-2026.jks` and its password file, named
  from `local.properties` (`walcott.signing.storeFile`, `.storePassword`, `.keyAlias`,
  `.keyPassword`). `local.properties` is gitignored.

**Back the keystore up somewhere that is not this laptop.** Losing it means no release can ever
be installed as an update again.

Without a key configured, `assembleRelease` produces an unsigned APK and `assembleDebug` uses the
SDK's throwaway debug key — which cannot be installed over a Device Owner build, and that is the
point: other developers can build and test, and cannot produce something a child's phone accepts.

## Signing a build by hand

```sh
scripts/sign-apk.sh <in.apk> <out.apk>
```

Aligns, signs with the key and the lineage (v3 only: neither v1 nor v2 can carry a rotation), and prints the signer. This is what CI
runs, and what the parent-sim harness needs for the emulator (see `parent-sim/README.md`): an AVD
that already carries the app accepts only the rotated signature.

`version.json` carries the APK's `sha256`; `Updater` refuses a download that does not match it,
and caps the download size. Both are defence in depth — the signature at install time is the gate.

## Rotating again

```sh
apksigner rotate --in signing/walcott.lineage --out signing/walcott.lineage \
  --old-signer --ks <current.jks> --new-signer --ks <next.jks>
```

Then move the secrets and `local.properties` to the next key. Never sign a release without the
lineage: a phone that took a rotated build will refuse it.
