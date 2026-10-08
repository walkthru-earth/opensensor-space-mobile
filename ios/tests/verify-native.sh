#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/opensensor-ios-tests.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
swiftc -module-cache-path "$test_dir/module-cache" \
  "$repo_root/ios/App/SensorArchive.swift" \
  "$repo_root/ios/App/SensorTransfer.swift" \
  "$repo_root/ios/tests/TransferChecks.swift" \
  "$repo_root/ios/tests/archive-main.swift" \
  -o "$test_dir/archive-checks"
"$test_dir/archive-checks"
