#!/usr/bin/env python3
"""Fails when a release APK carries the debug demo sensor (#310).

The demo sensor lives in `:core:demo` and only the debug build links it. This reads every
`classes*.dex` in the APK, and the R8 mapping when given, and fails on any demo marker. With
`--expect-present` it instead fails when the markers are missing, which proves on a debug APK that
the scan still finds the demo.

Usage, python3 tools/check_apk_no_demo.py <apk> [--mapping mapping.txt] [--expect-present]
"""

import argparse
import re
import sys
import zipfile

# Class descriptors in dex, class names in the R8 mapping, and the demo addresses as literals.
MARKERS = (
    b"space/opensensor/demo/",
    b"space.opensensor.demo.",
    b"DemoSensor",
    b"FakeTransport",
    b"FA:KE:",
)
DEX = re.compile(r"^classes\d*\.dex$")


def found_in(blob):
    return {marker.decode() for marker in MARKERS if marker in blob}


def scan(apk_path, mapping_path):
    hits = {}
    with zipfile.ZipFile(apk_path) as apk:
        dexes = [name for name in apk.namelist() if DEX.match(name)]
        if not dexes:
            raise SystemExit(f"::error::{apk_path} has no classes.dex")
        for name in dexes:
            markers = found_in(apk.read(name))
            if markers:
                hits[f"{apk_path}!{name}"] = markers
        print(f"{apk_path} scanned {' '.join(sorted(dexes))}")
    if mapping_path:
        with open(mapping_path, "rb") as mapping:
            markers = found_in(mapping.read())
        if markers:
            hits[mapping_path] = markers
        print(f"{mapping_path} scanned")
    return hits


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("apk")
    parser.add_argument("--mapping", help="the R8 mapping.txt of the same build")
    parser.add_argument("--expect-present", action="store_true", help="fail when the demo is missing, for a debug APK")
    args = parser.parse_args(argv)
    hits = scan(args.apk, args.mapping)
    if args.expect_present:
        if not hits:
            print(f"::error::{args.apk} has no demo marker, so this check no longer sees the demo sensor")
            return 1
        print(f"{args.apk} carries the demo sensor, as a debug build should")
        return 0
    for where, markers in sorted(hits.items()):
        print(f"::error::{where} carries the demo sensor ({', '.join(sorted(markers))})")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
