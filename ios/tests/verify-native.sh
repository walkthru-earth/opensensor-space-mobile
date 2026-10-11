#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/opensensor-ios-tests.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
swiftc -module-cache-path "$test_dir/module-cache" -swift-version 6 -strict-concurrency=complete -warnings-as-errors \
  "$repo_root/ios/App/SensorArchive.swift" \
  "$repo_root/ios/App/LanFraming.swift" \
  "$repo_root/ios/App/LanPolicy.swift" \
  "$repo_root/ios/App/RunSlot.swift" \
  "$repo_root/ios/App/BleGattQueue.swift" \
  "$repo_root/ios/App/BleBondFailure.swift" \
  "$repo_root/ios/tests/TransferChecks.swift" \
  "$repo_root/ios/tests/ArchiveChecks.swift" \
  "$repo_root/ios/tests/CancellationChecks.swift" \
  "$repo_root/ios/tests/BleGattChecks.swift" \
  "$repo_root/ios/tests/native-main.swift" \
  -o "$test_dir/archive-checks"
"$test_dir/archive-checks"
