#!/usr/bin/env python3
"""Fail when the live Discover Collection lists no cells file the app can draw (#257).

It applies the asset rules of DiscoverCollection.parse in :core:publicfeed. A cells asset is
keyed `cells_r<n>` or `h3_res=<n>`, or has any other key and is Parquet with one `h3_res=<n>`
segment in its href. `<n>` is 0 to 15 in plain decimal. An href segment that names another
resolution than the key, and a resolution listed twice, drop the asset. The map draws
resolutions 2 to 7 (ViewportCover), so a Collection with none of them is an empty map.
Keep this file in step with DiscoverCollection.cellsResolution.

Usage: check_discover_collection.py <collection.json>
"""

import json
import pathlib
import re
import sys
from collections import Counter

KEYS = ("cells_r", "h3_res=")
SEGMENT = "h3_res="
PARQUET = "application/vnd.apache.parquet"
DIGITS = re.compile(r"0|[1-9][0-9]?")
MAX_RES = 15
DRAWABLE = range(2, 8)


def resolution_of(digits: str) -> int | None:
    if not DIGITS.fullmatch(digits):
        return None
    res = int(digits)
    return res if res <= MAX_RES else None


def cells_resolution(key: str, asset: dict) -> int | None:
    href = str(asset.get("href", ""))
    path = href.split("?", 1)[0].split("#", 1)[0]
    segments = [s for s in path.split("/") if s.startswith(SEGMENT)]
    from_href = resolution_of(segments[0][len(SEGMENT):]) if len(segments) == 1 else None
    form = next((k for k in KEYS if key.startswith(k)), None)
    if form is None:
        return from_href if asset.get("type") == PARQUET else None
    from_key = resolution_of(key[len(form):])
    if from_key is None:
        return None
    return from_key if not segments or from_href == from_key else None


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    collection = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    if collection.get("type") != "Collection":
        print("::error title=Discover Collection::not a STAC Collection")
        return 1
    assets = collection.get("assets", {})
    listed = {key: cells_resolution(key, asset) for key, asset in assets.items()}
    times = Counter(res for res in listed.values() if res is not None)
    cells = sorted(res for res, n in times.items() if n == 1)
    failed = False
    for key, res in sorted(listed.items()):
        looks_like_cells = key.startswith(KEYS) or SEGMENT in str(assets[key].get("href", ""))
        if looks_like_cells and (res is None or times[res] > 1):
            print(f"::error title=Discover cells asset dropped::{key} is ambiguous or names no resolution")
            failed = True
    drawable = [res for res in cells if res in DRAWABLE]
    if not drawable:
        print(f"::error title=Discover map empty::no cells asset at resolution 2 to 7, found {cells}")
        failed = True
    print(f"{len(assets)} assets, cells at resolutions {cells}, drawable {drawable}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
