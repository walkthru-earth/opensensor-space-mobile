#!/usr/bin/env bash
# ktlint for the pre-commit hook: formats the staged Kotlin files in place, then lints them.
# Rules come from .editorconfig (Android Studio style, no line limit, house-style rules off).
#
# The binary is fetched once into ~/.cache/ktlint and pinned by SHA-256, so the hook is
# deterministic and needs no Gradle start-up (a full `./gradlew verify` runs on pre-push).
# Requires a JRE ≥ 17 on PATH or JAVA_HOME (Android Studio's JBR works:
#   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home").
set -euo pipefail

KTLINT_VERSION="1.8.0"
KTLINT_SHA256="a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/ktlint"
BIN="$CACHE_DIR/ktlint-$KTLINT_VERSION"

if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! command -v java >/dev/null 2>&1; then
  echo "ktlint hook: no 'java' on PATH; set JAVA_HOME (Android Studio's JBR is fine)." >&2
  exit 1
fi

sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

if [ ! -x "$BIN" ] || [ "$(sha256 "$BIN")" != "$KTLINT_SHA256" ]; then
  mkdir -p "$CACHE_DIR"
  echo "ktlint hook: fetching ktlint $KTLINT_VERSION" >&2
  curl -fsSL -o "$BIN.tmp" "https://github.com/ktlint/ktlint/releases/download/$KTLINT_VERSION/ktlint"
  if [ "$(sha256 "$BIN.tmp")" != "$KTLINT_SHA256" ]; then
    echo "ktlint hook: checksum mismatch for the downloaded binary; refusing to run it." >&2
    rm -f "$BIN.tmp"; exit 1
  fi
  chmod +x "$BIN.tmp" && mv "$BIN.tmp" "$BIN"
fi

[ "$#" -gt 0 ] || exit 0
mode="${KTLINT_MODE:-format}"
if [ "$mode" = "format" ]; then
  "$BIN" --relative --format "$@" >/dev/null 2>&1 || true   # fix what can be fixed, quietly
fi
exec "$BIN" --relative "$@"                                   # report what remains (non-zero if anything)
