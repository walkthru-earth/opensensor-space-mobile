#!/usr/bin/env bash
# Release signing, end to end and idempotent:
#   1. generate secrets/release.jks (PKCS12, RSA 4096, 30 years) with random passwords — once;
#   2. write secrets/.env with the AQSYNC_* variables app/build.gradle.kts reads, so
#        set -a; source secrets/.env; set +a; ./gradlew :app:assembleRelease
#      signs a local release with the same key CI uses;
#   3. upload the four GitHub Actions secrets release.yml expects (KEYSTORE_BASE64, KEYSTORE_PASSWORD,
#      KEY_ALIAS, KEY_PASSWORD) with `gh secret set`.
# Nothing under secrets/ is tracked (.gitignore) and no password is ever printed.
#
# BACK THE KEYSTORE UP (password manager / offline). Losing it means no future release can install
# over an existing one. Usage: tools/setup-signing.sh [--no-upload]
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="${AQSYNC_GH_REPO:-walkthru-earth/m5stack-aq-android}"
DIR="secrets"
KS="$DIR/release.jks"
ENV="$DIR/.env"
ALIAS="aqsync"

JAVA_BIN="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}/bin"
KEYTOOL="$JAVA_BIN/keytool"; command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL="$(command -v keytool)"

for f in "$KS" "$ENV"; do git check-ignore -q "$f" || { echo "refusing: $f is not git-ignored" >&2; exit 1; }; done
mkdir -p "$DIR"; chmod 700 "$DIR"

if [ ! -f "$KS" ]; then
  # One password for store and key: PKCS12 does not support different ones.
  PASS="$(openssl rand -base64 33 | tr -d '\n=/+' | cut -c1-40)"
  echo "generating $KS (RSA 4096, alias $ALIAS, valid 30 years)…"
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10950 \
    -dname "CN=AQ Sync, O=walkthru.earth" \
    -storepass "$PASS" -keypass "$PASS" >/dev/null 2>&1
  umask 077
  cat > "$ENV" <<EOF
# Release signing for AQ Sync — generated $(date -u +%Y-%m-%dT%H:%M:%SZ) by tools/setup-signing.sh. NEVER COMMIT.
# Local signed build:  set -a; source secrets/.env; set +a; ./gradlew :app:assembleRelease
AQSYNC_KEYSTORE_FILE=$KS
AQSYNC_KEYSTORE_PASSWORD=$PASS
AQSYNC_KEY_ALIAS=$ALIAS
AQSYNC_KEY_PASSWORD=$PASS
EOF
  chmod 600 "$KS" "$ENV"
  echo "wrote $KS and $ENV (mode 600)"
else
  echo "$KS exists; reusing it (delete both files to regenerate — you almost never want that)"
fi

# shellcheck disable=SC1090
set -a; source "$ENV"; set +a
"$KEYTOOL" -list -keystore "$KS" -storepass "$AQSYNC_KEYSTORE_PASSWORD" -alias "$AQSYNC_KEY_ALIAS" >/dev/null \
  || { echo "keystore/password mismatch between $KS and $ENV" >&2; exit 1; }
echo "certificate: $("$KEYTOOL" -list -v -keystore "$KS" -storepass "$AQSYNC_KEYSTORE_PASSWORD" -alias "$AQSYNC_KEY_ALIAS" | grep -m1 'SHA256:' | sed 's/^[[:space:]]*//')"

if [ "${1:-}" = "--no-upload" ]; then exit 0; fi
command -v gh >/dev/null 2>&1 || { echo "gh is not installed; rerun without upload or install it" >&2; exit 1; }
echo "uploading GitHub Actions secrets to $REPO…"
base64 < "$KS" | tr -d '\n'              | gh secret set KEYSTORE_BASE64   --repo "$REPO"
printf '%s' "$AQSYNC_KEYSTORE_PASSWORD"  | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$AQSYNC_KEY_ALIAS"          | gh secret set KEY_ALIAS         --repo "$REPO"
printf '%s' "$AQSYNC_KEY_PASSWORD"       | gh secret set KEY_PASSWORD      --repo "$REPO"
gh secret list --repo "$REPO"
echo "done. The next push to main (or: gh workflow run release) publishes a release signed with this key."
