#!/usr/bin/env python3
"""Fails when an APK ships native code for other ABIs than the app supports (#61).

The app supports arm64-v8a and armeabi-v7a. Every supported ABI must carry the MapLibre, zstd and
H3 libraries, so a phone never loads a partial set. Usage, python3 tools/check_apk_abis.py <apk>...
"""

import re
import sys
import zipfile

SUPPORTED = {"arm64-v8a", "armeabi-v7a"}
REQUIRED = {
    "maplibre": re.compile(r"^libmaplibre-native-c\.so$"),
    "zstd": re.compile(r"^libzstd-jni.*\.so$"),
    "h3": re.compile(r"^libh3-java\.so$"),
}


def check(path):
    libs = {}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                libs.setdefault(parts[1], set()).add(parts[2])
    problems = []
    found = set(libs)
    if found != SUPPORTED:
        problems.append(f"ABIs {sorted(found)}, expected {sorted(SUPPORTED)}")
    for abi in sorted(found & SUPPORTED):
        for lib, pattern in REQUIRED.items():
            if not any(pattern.match(so) for so in libs[abi]):
                problems.append(f"{abi} has no {lib} library")
    for abi in sorted(found):
        print(f"{path} lib/{abi}/ {' '.join(sorted(libs[abi]))}")
    return problems


def main(paths):
    if not paths:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    failed = False
    for path in paths:
        for problem in check(path):
            print(f"::error::{path} {problem}")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
