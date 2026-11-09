#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Write the bundled offline place list of the map search from GeoNames cities15000.

    uv run tools/places/make_places.py                       # downloads cities15000.zip
    uv run tools/places/make_places.py path/to/cities15000.zip

GeoNames (https://www.geonames.org) publishes cities15000, every populated place of 15,000
people or more, under CC BY 4.0. The map info credits it. This script keeps only what the search
needs and writes one gzip file to

    ui/map/src/commonMain/composeResources/files/places/cities15000.tsv.gz

One UTF-8 line per place, tab separated, sorted by population from largest (ties by GeoNames id)

    name  ascii  arabic  country  lat  lon  population

- `name` is the GeoNames name. `ascii` is its ASCII name, empty when it equals `name`.
- `arabic` is empty for every place, so both locales show `name`. The untagged alternate names of
  cities15000 mix old, district and colloquial names (Casablanca would read أنفا), so they are not
  used. Tagged Arabic names from alternateNamesV2 can fill this column later.
- `country` is the ISO 3166 alpha-2 code.
- `lat` and `lon` are rounded to 2 decimals, about 1 km, enough to centre the map on a city.
- `population` is the GeoNames population rounded to 2 significant digits. The line order keeps
  the exact ranking.

The gzip header carries no name and no time, so running the script again on the same download
writes byte-identical output.
"""
from __future__ import annotations

import gzip
import io
import sys
import urllib.request
import zipfile
from pathlib import Path

SOURCE = "https://download.geonames.org/export/dump/cities15000.zip"
OUT = Path(__file__).resolve().parents[2] / "ui/map/src/commonMain/composeResources/files/places/cities15000.tsv.gz"

def read_zip(arg: str | None) -> bytes:
    if arg:
        return Path(arg).read_bytes()
    with urllib.request.urlopen(SOURCE) as r:  # noqa: S310, a fixed https URL
        return r.read()


def clean(s: str) -> str:
    return s.replace("\t", " ").replace("\n", " ").strip()


def two_digits(n: int) -> int:
    """[n] rounded to 2 significant digits, so the file stays small."""
    if n < 100:
        return n
    step = 10 ** (len(str(n)) - 2)
    return (n + step // 2) // step * step


def main() -> None:
    raw = read_zip(sys.argv[1] if len(sys.argv) > 1 else None)
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        text = z.read("cities15000.txt").decode("utf-8")
    rows = []
    for line in text.splitlines():
        f = line.split("\t")
        if len(f) < 15:
            continue
        geoname_id, name, ascii_name = int(f[0]), clean(f[1]), clean(f[2])
        lat, lon, country, population = float(f[4]), float(f[5]), f[8].strip(), int(f[14] or 0)
        if not name or len(country) != 2:
            continue
        rows.append((population, geoname_id, name, "" if ascii_name == name else ascii_name, country, lat, lon))
    rows.sort(key=lambda r: (-r[0], r[1]))
    out = io.StringIO()
    for population, _, name, ascii_name, country, lat, lon in rows:
        out.write(f"{name}\t{ascii_name}\t\t{country}\t{lat:.2f}\t{lon:.2f}\t{two_digits(population)}\n")
    data = out.getvalue().encode("utf-8")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, compresslevel=9, mtime=0) as g:
        g.write(data)
    OUT.write_bytes(buf.getvalue())
    print(f"{len(rows)} places, {len(data)} bytes plain, {len(buf.getvalue())} bytes gzip, {OUT}")


if __name__ == "__main__":
    main()
