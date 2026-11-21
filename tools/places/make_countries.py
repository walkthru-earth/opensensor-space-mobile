#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Write the bundled offline country polygons from tabaqat/geocoding-cng world_countries_simple.parquet.

    uv run tools/places/make_countries.py
    uv run tools/places/make_countries.py path/to/world_countries_simple.parquet

Source: https://data.source.coop/tabaqat/geocoding-cng/v0.4.4/world_countries_simple.parquet
(Natural Earth simplified country boundaries).

Repacks into GZIP-compressed GeoParquet (readable by :core:parquet's built-in codecs on Android
and iOS without ZSTD) with only `iso_a2` and `geometry`, fixing the Natural Earth `-99` / `CN-TW`
sentinel codes for sovereign states so every bundled country has a valid ISO 3166-1 alpha-2 code.

Requires `duckdb` on PATH.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

SOURCE = "https://data.source.coop/tabaqat/geocoding-cng/v0.4.4/world_countries_simple.parquet"
OUT = Path(__file__).resolve().parents[2] / "ui/map/src/commonMain/composeResources/files/places/world_countries_simple.parquet"

SQL = """
COPY (
  SELECT
    CASE iso_a3
      WHEN 'US1' THEN 'US'
      WHEN 'GB1' THEN 'GB'
      WHEN 'NOR' THEN 'NO'
      WHEN 'DN1' THEN 'DK'
      WHEN 'FR1' THEN 'FR'
      WHEN 'IS1' THEN 'IL'
      WHEN 'NL1' THEN 'NL'
      WHEN 'NZ1' THEN 'NZ'
      WHEN 'AU1' THEN 'AU'
      WHEN 'CH1' THEN 'CN'
      WHEN 'TWN' THEN 'TW'
      WHEN 'FI1' THEN 'FI'
      ELSE CASE WHEN iso_a2 ~ '^[A-Z]{2}$' THEN iso_a2 ELSE '' END
    END AS iso_a2,
    geometry
  FROM '{src}'
  WHERE iso_a3 IN ('US1','GB1','NOR','DN1','FR1','IS1','NL1','NZ1','AU1','CH1','TWN','FI1')
     OR iso_a2 ~ '^[A-Z]{2}$'
  ORDER BY iso_a2
) TO '{out}' (FORMAT PARQUET, COMPRESSION GZIP);
"""


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    if len(sys.argv) > 1:
        src = Path(sys.argv[1]).resolve()
        subprocess.run(["duckdb", "-c", SQL.format(src=src, out=OUT)], check=True)
    else:
        with tempfile.NamedTemporaryFile(suffix=".parquet") as tmp:
            with urllib.request.urlopen(SOURCE) as r:  # noqa: S310, a fixed https URL
                tmp.write(r.read())
            tmp.flush()
            subprocess.run(["duckdb", "-c", SQL.format(src=tmp.name, out=OUT)], check=True)
    print(f"{OUT.stat().st_size} bytes, {OUT}")


if __name__ == "__main__":
    main()
