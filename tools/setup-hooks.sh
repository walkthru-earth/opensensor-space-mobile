#!/usr/bin/env bash
# Installs the versioned git hooks from .pre-commit-config.yaml into this clone (once per clone).
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v pre-commit >/dev/null 2>&1; then
  echo "pre-commit is not installed. One of:  brew install pre-commit  |  pipx install pre-commit  |  pip install --user pre-commit" >&2
  exit 1
fi
pre-commit install --hook-type pre-commit --hook-type pre-push
echo "Hooks installed. Try them on everything once:  pre-commit run --all-files"
