#!/usr/bin/env bash
# Signs an APK with the current release key AND the signing lineage, so that a phone carrying a
# build signed with the ORIGINAL (2026-07, now public) key accepts it as an update and, from then
# on, refuses anything signed with that original key alone. See docs/signing.md.
#
# Usage: scripts/sign-apk.sh <in.apk> <out.apk>
#
# The key comes from SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS /
# SIGNING_KEY_PASSWORD (CI), falling back to walcott.signing.* in local.properties (a dev machine).
# Needs the Android SDK build-tools (ANDROID_HOME or ANDROID_SDK_ROOT, else local.properties sdk.dir).
set -euo pipefail

in_apk="${1:?usage: sign-apk.sh <in.apk> <out.apk>}"
out_apk="${2:?usage: sign-apk.sh <in.apk> <out.apk>}"
repo="$(cd "$(dirname "$0")/.." && pwd)"

local_prop() { grep -E "^$1=" "$repo/local.properties" 2>/dev/null | head -1 | cut -d= -f2- || true; }

store="${SIGNING_STORE_FILE:-$(local_prop walcott.signing.storeFile)}"
store_pass="${SIGNING_STORE_PASSWORD:-$(local_prop walcott.signing.storePassword)}"
key_alias="${SIGNING_KEY_ALIAS:-$(local_prop walcott.signing.keyAlias)}"
key_pass="${SIGNING_KEY_PASSWORD:-$(local_prop walcott.signing.keyPassword)}"
key_alias="${key_alias:-walcott}"
if [[ -z "$store" || -z "$store_pass" ]]; then
  echo "sign-apk: no signing key configured (SIGNING_* env or walcott.signing.* in local.properties)" >&2
  exit 2
fi
[[ -f "$store" ]] || { echo "sign-apk: keystore not found: $store" >&2; exit 2; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(local_prop sdk.dir)}}"
build_tools="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
[[ -n "$build_tools" ]] || { echo "sign-apk: no build-tools under $sdk" >&2; exit 2; }
lineage="$repo/signing/walcott.lineage"
[[ -f "$lineage" ]] || { echo "sign-apk: lineage missing: $lineage" >&2; exit 2; }

aligned="$(mktemp --suffix=.apk)"
trap 'rm -f "$aligned"' EXIT
# apksigner keeps alignment but never creates it; an unsigned AGP output is aligned already and
# this is a no-op there, while a re-signed APK from anywhere else gets it here.
#
# v3 only. Neither v1 nor v2 can express a rotation, so with a lineage apksigner would need the
# ORIGINAL key to produce them — and the original key is exactly what no build may depend on.
# v3 is understood from API 28 and minSdk is 29; it carries the lineage, and it is enough.
# --rotation-min-sdk-version puts the rotated signer in the v3.0 block for every supported
# device; without it apksigner keeps the rotation for API 33+ (v3.1) and wants the original
# signer for the range below.
"${build_tools}zipalign" -p -f 4 "$in_apk" "$aligned"
"${build_tools}apksigner" sign \
  --ks "$store" --ks-pass "pass:$store_pass" --ks-key-alias "$key_alias" --key-pass "pass:${key_pass:-$store_pass}" \
  --lineage "$lineage" \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
  --min-sdk-version 29 --rotation-min-sdk-version 29 \
  --out "$out_apk" "$aligned"
"${build_tools}apksigner" verify --print-certs "$out_apk" | grep -E "Signer .* certificate SHA-256|Verified using v3" || true
echo "sign-apk: signed $out_apk"
