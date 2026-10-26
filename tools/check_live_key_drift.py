#!/usr/bin/env python3
"""Fail when aq-parquet has a LIVE dictionary digest the app fixtures do not know.

The firmware freezes every released LIVE key set in aq-parquet
tools/fixtures/live_keys/*.json. The app copies them into LiveKeyFixturesTest.
A digest that is only on the firmware side means a firmware release the app has
no bundled board template for, so it shows no live readings until the template
and profile files are swapped in (issues #153 and #184).

Usage: check_live_key_drift.py <aq-parquet fixtures dir> <LiveKeyFixturesTest.kt>
"""

import json
import pathlib
import re
import sys

DIGEST = re.compile(r'"([0-9a-f]{64})"')


def firmware_digests(fixtures: pathlib.Path) -> dict[str, str]:
    found = {}
    for path in sorted(fixtures.glob("*.json")):
        board = json.loads(path.read_text(encoding="utf-8"))
        for digest in board.get("digests", {}):
            found[digest] = path.name
    return found


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    fixtures = pathlib.Path(sys.argv[1])
    test_file = pathlib.Path(sys.argv[2])
    firmware = firmware_digests(fixtures)
    if not firmware:
        print(f"No digests found in {fixtures}", file=sys.stderr)
        return 2
    app = set(DIGEST.findall(test_file.read_text(encoding="utf-8")))
    missing = {d: f for d, f in firmware.items() if d not in app}
    for digest, source in sorted(missing.items(), key=lambda item: item[1]):
        print(f"::error title=Unknown LIVE digest::{source} has {digest}, LiveKeyFixturesTest does not")
    print(f"{len(firmware)} firmware digests, {len(app)} app digests, {len(missing)} missing")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
