#!/usr/bin/env python3
"""Fail when the live Discover Collection lists no cells file the app can draw (#257).

It applies the asset rules of DiscoverCollection.parse in :core:publicfeed. A cells asset is
keyed `cells_r<n>` or `h3_res=<n>`, or has any other key and is Parquet with one `h3_res=<n>`
segment in its href. `<n>` is 0 to 15 in plain decimal. An href segment that names another
resolution than the key, and a resolution listed twice, drop the asset. Then the href must be
relative (no `..`, no leading `/`, no `:` or `\\`) or https on the host of the feed root, else
the asset is dropped alone. The map draws resolutions 2 to 7 (ViewportCover), so a Collection
with none of them is an empty map, the only failure. A dropped asset that looks like cells is
reported as a warning. Keep this file in step with DiscoverCollection.cellsResolution and resolve.

Usage: check_discover_collection.py <collection.json> <feed root URL>
       check_discover_collection.py --self-test
"""

import json
import pathlib
import re
import sys
from collections import Counter

KEYS = ("cells_r", "h3_res=")
SEGMENT = "h3_res="
PARQUET = "application/vnd.apache.parquet"
HTTPS = "https://"
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


def authority(url: str) -> str | None:
    """The host and port of an https url, or None for any other URL or one with user info."""
    if not url.startswith(HTTPS):
        return None
    rest = url[len(HTTPS):]
    ends = [i for i in (rest.find(c) for c in "/?#") if i >= 0]
    host = rest[: min(ends) if ends else len(rest)].lower()
    return host if host and "@" not in host and "\\" not in host else None


def resolve(base: str, href: str) -> str | None:
    if href.startswith(HTTPS):
        return href if authority(href) is not None and authority(href) == authority(base) else None
    if ":" in href or "\\" in href or href.startswith("/") or ".." in href.split("/"):
        return None
    return f"{base}/{href.removeprefix('./')}"


def check(collection: dict, root: str) -> tuple[bool, list[str]]:
    """Whether the map has drawable cells, and the lines to print."""
    if collection.get("type") != "Collection":
        return False, ["::error title=Discover Collection::not a STAC Collection"]
    base = root.rstrip("/")
    assets = collection.get("assets", {})
    listed = {key: cells_resolution(key, asset) for key, asset in assets.items()}
    times = Counter(res for res in listed.values() if res is not None)
    cells = {}
    lines = []
    for key, res in sorted(listed.items()):
        href = str(assets[key].get("href", ""))
        url = resolve(base, href) if res is not None and times[res] == 1 else None
        if url is not None:
            cells[res] = url
        elif key.startswith(KEYS) or SEGMENT in href:
            lines.append(f"::warning title=Discover cells asset dropped::{key} is ambiguous, unsafe or names no resolution")
    drawable = sorted(res for res in cells if res in DRAWABLE)
    if not drawable:
        lines.append(f"::error title=Discover map empty::no cells asset at resolution 2 to 7, found {sorted(cells)}")
    lines.append(f"{len(assets)} assets, cells at resolutions {sorted(cells)}, drawable {drawable}")
    return bool(drawable), lines


def self_test() -> int:
    root = "https://example.test/feed/"

    def ok(assets: dict) -> bool:
        return check({"type": "Collection", "assets": assets}, root)[0]

    def cell(href: str, kind: str | None = None) -> dict:
        return {"href": href} if kind is None else {"href": href, "type": kind}

    cases = [
        (True, {"cells_r5": cell("./cells/h3_res=5/data.parquet")}),
        (True, {"h3_res=5": cell("cells/h3_res=5/data.parquet")}),
        (True, {"r5": cell("./cells/h3_res=5/data.parquet", PARQUET)}),
        (True, {"cells_r5": cell("https://EXAMPLE.test/mirror/r5.parquet")}),
        (False, {"r5": cell("./cells/h3_res=5/data.parquet", "image/png")}),
        (False, {"cells_r5": cell("http://example.test/feed/r5.parquet")}),
        (False, {"cells_r5": cell("https://evil.test/r5.parquet")}),
        (False, {"cells_r5": cell("https://example.test@evil.test/r5.parquet")}),
        (False, {"cells_r5": cell("../other/h3_res=5/data.parquet")}),
        (False, {"cells_r5": cell("/abs/h3_res=5/data.parquet")}),
        (False, {"cells_r5": cell("./cells/h3_res=6/data.parquet")}),
        (False, {"cells_r16": cell("./cells/data.parquet"), "cells_r05": cell("./cells/data.parquet")}),
        (False, {"cells_r5": cell("./a/h3_res=5/x.parquet"), "h3_res=5": cell("./b/h3_res=5/x.parquet")}),
        (False, {"cells_r1": cell("./cells/h3_res=1/data.parquet"), "cells_r9": cell("./cells/h3_res=9/data.parquet")}),
        # An asset the app ignores warns but leaves drawable cells, so the check passes.
        (True, {"cells_r2": cell("./cells/h3_res=2/data.parquet"), "manifest": cell("./h3_res=3/manifest.json", "application/json")}),
        (True, {"cells_r2": cell("./cells/h3_res=2/data.parquet"), "cells_r3": cell("http://example.test/r3.parquet")}),
    ]
    failed = [i for i, (want, assets) in enumerate(cases) if ok(assets) != want]
    if check({"type": "Feature"}, root)[0]:
        failed.append("not a Collection")
    for case in failed:
        print(f"self test case {case} failed", file=sys.stderr)
    print(f"{len(cases)} self test cases, {len(failed)} failed")
    return 1 if failed else 0


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[-2], file=sys.stderr)
        return 2
    passed, lines = check(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")), sys.argv[2])
    print("\n".join(lines))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
