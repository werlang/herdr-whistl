#!/usr/bin/env bash
# Sign a release APK with the project keystore, keeping the key off GitHub.
#
#   scripts/sign-release.sh <unsigned.apk> [output.apk]
#
# Needed because the release workflow publishes an UNSIGNED apk: the keystore and its
# password live only on the maintainer's machines. Set these or let the script find them:
#
#   WHISTL_KEYSTORE   path to the PKCS12 keystore        (default ~/.android-signing/herdr-whistl/release.jks)
#   WHISTL_KEYSTORE_PASSWORD_FILE  file holding the pass (default beside the keystore, "keystore.password")
#   WHISTL_KEY_ALIAS  key alias                          (default herdr-whistl)
#   ANDROID_HOME      Android SDK                        (default /opt/homebrew/share/android-commandlinetools)
#
# The password never appears in argv: apksigner reads it from a 0600 temp file, and the key
# password is omitted so apksigner reuses the store password (PKCS12 keeps them the same).
set -euo pipefail

IN="${1:?usage: sign-release.sh <unsigned.apk> [output.apk]}"
OUT="${2:-${IN%-unsigned.apk}-signed.apk}"

KEYSTORE="${WHISTL_KEYSTORE:-$HOME/.android-signing/herdr-whistl/release.jks}"
PWFILE="${WHISTL_KEYSTORE_PASSWORD_FILE:-$(dirname "$KEYSTORE")/keystore.password}"
ALIAS="${WHISTL_KEY_ALIAS:-herdr-whistl}"
ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -x "$BT/apksigner" ] || { echo "apksigner not found under $ANDROID_HOME"; exit 1; }
[ -f "$KEYSTORE" ] || { echo "keystore not found: $KEYSTORE"; exit 1; }
[ -f "$PWFILE" ] || { echo "password file not found: $PWFILE"; exit 1; }

TMP="$(mktemp -d)"
umask 077
trap 'rm -rf "$TMP"' EXIT
tr -d '\r\n' < "$PWFILE" > "$TMP/pw"
chmod 600 "$TMP/pw"

"$BT/zipalign" -p -f 4 "$IN" "$TMP/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass "file:$TMP/pw" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$TMP/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT" | head -3
sha256sum "$OUT" 2>/dev/null || shasum -a 256 "$OUT"
echo "signed: $OUT"
echo
echo "Publish it next to the unsigned build:"
echo "  gh release upload <tag> \"$OUT\" --repo werlang/herdr-whistl --clobber"
