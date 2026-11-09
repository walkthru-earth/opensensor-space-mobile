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
- `arabic` is the curated Arabic name of the cities in `CURATED_ARABIC`, by GeoNames id, the list
  Discover searched before this file. It is empty for every other place, so both languages show
  `name`. The untagged alternate names of cities15000 mix old, district and colloquial names
  (Casablanca would read أنفا), so they are not used. Tagged Arabic names from alternateNamesV2 can
  fill this column later.
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

# Reviewed Arabic names by GeoNames id, the large cities and the main Ghana cities.
CURATED_ARABIC: dict[int, str] = {
    2306104: "أكرا",  # Accra, GH
    344979: "أديس أبابا",  # Addis Ababa, ET
    361058: "الإسكندرية",  # Alexandria, EG
    2759794: "أمستردام",  # Amsterdam, NL
    264371: "أثينا",  # Athens, GR
    1609350: "بانكوك",  # Bangkok, TH
    3128760: "برشلونة",  # Barcelona, ES
    1816670: "بكين",  # Beijing, CN
    2950159: "برلين",  # Berlin, DE
    3688689: "بوغوتا",  # Bogotá, CO
    3435910: "بوينس آيرس",  # Buenos Aires, AR
    360630: "القاهرة",  # Cairo, EG
    2302357: "كيب كوست",  # Cape Coast, GH
    3369157: "كيب تاون",  # Cape Town, ZA
    2553604: "الدار البيضاء",  # Casablanca, MA
    4887398: "شيكاغو",  # Chicago, US
    1273294: "دلهي",  # Delhi, IN
    292223: "دبي",  # Dubai, AE
    2911298: "هامبورغ",  # Hamburg, DE
    745044: "إسطنبول",  # Istanbul, TR
    1642911: "جاكرتا",  # Jakarta, ID
    993800: "جوهانسبرغ",  # Johannesburg, ZA
    2298890: "كوماسي",  # Kumasi, GH
    2332459: "لاغوس",  # Lagos, NG
    3936456: "ليما",  # Lima, PE
    2267057: "لشبونة",  # Lisbon, PT
    2643743: "لندن",  # London, GB
    5368361: "لوس أنجلوس",  # Los Angeles, US
    3117735: "مدريد",  # Madrid, ES
    1701668: "مانيلا",  # Manila, PH
    3530597: "مكسيكو سيتي",  # Mexico City, MX
    3173435: "ميلانو",  # Milan, IT
    1275339: "مومباي",  # Mumbai, IN
    2867714: "ميونخ",  # Munich, DE
    184745: "نيروبي",  # Nairobi, KE
    5128581: "نيويورك",  # New York, US
    2988507: "باريس",  # Paris, FR
    2735943: "بورتو",  # Porto, PT
    3169070: "روما",  # Rome, IT
    5391959: "سان فرانسيسكو",  # San Francisco, US
    3448439: "ساو باولو",  # São Paulo, BR
    1835848: "سول",  # Seoul, KR
    1880252: "سنغافورة",  # Singapore, SG
    2673730: "ستوكهولم",  # Stockholm, SE
    2147714: "سيدني",  # Sydney, AU
    2294915: "تاكورادي",  # Takoradi, GH
    2294877: "تامالي",  # Tamale, GH
    2294700: "تيما",  # Tema, GH
    1850147: "طوكيو",  # Tokyo, JP
    6167865: "تورونتو",  # Toronto, CA
    2464470: "تونس",  # Tunis, TN
    2761369: "فيينا",  # Vienna, AT
    756135: "وارسو",  # Warsaw, PL
}


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
        arabic = CURATED_ARABIC.get(geoname_id, "")
        rows.append((population, geoname_id, name, "" if ascii_name == name else ascii_name, arabic, country, lat, lon))
    rows.sort(key=lambda r: (-r[0], r[1]))
    out = io.StringIO()
    found = {r[1] for r in rows}
    missing = sorted(set(CURATED_ARABIC) - found)
    if missing:
        sys.exit(f"curated Arabic names for missing GeoNames ids {missing}")
    for population, _, name, ascii_name, arabic, country, lat, lon in rows:
        out.write(f"{name}\t{ascii_name}\t{arabic}\t{country}\t{lat:.2f}\t{lon:.2f}\t{two_digits(population)}\n")
    data = out.getvalue().encode("utf-8")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, compresslevel=9, mtime=0) as g:
        g.write(data)
    OUT.write_bytes(buf.getvalue())
    print(f"{len(rows)} places, {len(data)} bytes plain, {len(buf.getvalue())} bytes gzip, {OUT}")


if __name__ == "__main__":
    main()
