#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["h3==4.5.0", "pyarrow==25.0.1"]
# ///
"""Write a tiny OpenAQ aggregates fixture in the live daily layout, for :core:publicfeed tests.

    uv run tools/discover-fixture/make_openaq_fixture.py

The live collection is
`https://data.source.coop/walkthru-earth/opensensor-space/openaq-aggregates/`, written by DuckDB:

    manifest.json                    as_of (YYYY-MM-DD), generation, outputs, cell_properties
    cells/h3_res=<1..9>/data.parquet one row per h3_index and station local date

- Columns `h3_index` INT64, `date` DATE (INT32 days since 1970-01-01), `devices` INT32 (stations,
  may be 1), `<id>__n` INT32 and `<id>__mean`, `__min`, `__max` DOUBLE, every column optional and
  null when not measured. Rows sort by `h3_index` then `date`.
- SNAPPY, dictionary encoding, one row group (row groups of 16384), min and max statistics, no
  page index, as DuckDB writes it.
- Key value metadata `feed`, `dataset`, `window` (`1d`), `h3_res`, `generation`, `crowd_rule` and
  `dictionary` (the properties, the same JSON shape as the bundled sample).

Every number is made up and written by hand, so the tests can state the expected fold. The output
is deterministic. Four resolution 5 cells over ten days, 2026-09-29 to 2026-10-08 (as_of):

- Cairo: PM2.5 most days with a gap on 10-03 and 10-04, temperature on two days, 1 to 3 stations.
- Accra: one station, only 09-29 to 10-01, all before the 7 days ending at as_of.
- Lagos: humidity only, PM2.5 null every day.
- Nairobi: PM10 on 10-05, PM2.5 on 10-07, a row on 10-08 with nothing measured.
"""
from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import h3
import pyarrow as pa
import pyarrow.parquet as pq

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "core/publicfeed/src/commonTest/fixtures/openaq-aggregates"
RES = 5
AS_OF = "2026-10-08"
GENERATION = "fixture-0001"

PROPERTIES = [
    {"id": "pm25", "label": "PM2.5", "unit": "µg/m³", "category": "Air", "precision": 0.1},
    {"id": "pm10", "label": "PM10", "unit": "µg/m³", "category": "Air", "precision": 0.1},
    {"id": "pm1", "label": "PM1", "unit": "µg/m³", "category": "Air", "precision": 0.1},
    {"id": "no2", "label": "NO2", "unit": "ppm", "category": "Air", "precision": 0.001},
    {"id": "temperature", "label": "Temperature", "unit": "°C", "category": "Weather", "precision": 0.1},
    {"id": "humidity", "label": "Humidity", "unit": "%", "category": "Weather", "precision": 1},
    {"id": "um003", "label": "PM0.3 count", "unit": "particles/cm³", "category": "Air", "precision": 1},
]
PROPERTY_IDS = [p["id"] for p in PROPERTIES]

CELLS = {
    "cairo": h3.latlng_to_cell(30.044, 31.236, RES),
    "accra": h3.latlng_to_cell(5.603, -0.187, RES),
    "lagos": h3.latlng_to_cell(6.524, 3.379, RES),
    "nairobi": h3.latlng_to_cell(-1.286, 36.817, RES),
}

# (cell, day of October or (9, day) for September, devices, {property: (n, mean, min, max)})
ROWS = [
    ("cairo", (9, 30), 2, {"pm25": (2, 60.0, 40.0, 90.0)}),
    ("cairo", 2, 1, {"pm25": (1, 30.0, 20.0, 45.0)}),
    ("cairo", 5, 3, {"pm25": (3, 50.0, 35.0, 70.0), "temperature": (3, 28.0, 22.0, 33.0)}),
    ("cairo", 6, 2, {"pm25": (2, 40.0, 30.0, 55.0)}),
    ("cairo", 8, 2, {"pm25": (2, 20.0, 12.0, 31.0), "temperature": (2, 26.0, 21.0, 30.0)}),
    ("accra", (9, 29), 1, {"pm25": (1, 15.0, 10.0, 21.0)}),
    ("accra", (9, 30), 1, {"pm25": (1, 17.0, 11.0, 25.0)}),
    ("accra", 1, 1, {"pm25": (1, 16.0, 12.0, 20.0)}),
    ("lagos", 3, 1, {"humidity": (1, 80.0, 70.0, 91.0)}),
    ("lagos", 7, 1, {"humidity": (1, 70.0, 64.0, 77.0)}),
    ("nairobi", 5, 1, {"pm10": (1, 33.0, 25.0, 41.0)}),
    ("nairobi", 7, 2, {"pm25": (2, 12.0, 8.0, 17.0)}),
    ("nairobi", 8, 4, {}),
]


def schema() -> pa.Schema:
    fields = [pa.field("h3_index", pa.int64()), pa.field("date", pa.date32()), pa.field("devices", pa.int32())]
    for pid in PROPERTY_IDS:
        fields.append(pa.field(f"{pid}__n", pa.int32()))
        fields += [pa.field(f"{pid}__{s}", pa.float64()) for s in ("mean", "min", "max")]
    return pa.schema(fields)


def main() -> None:
    s = schema()
    columns: dict[str, list] = {f.name: [] for f in s}
    rows = []
    for cell, day, devices, stats in ROWS:
        month, dom = day if isinstance(day, tuple) else (10, day)
        rows.append((h3.str_to_int(CELLS[cell]), date(2026, month, dom), devices, stats))
    rows.sort(key=lambda r: (r[0], r[1]))
    for index, day, devices, stats in rows:
        columns["h3_index"].append(index)
        columns["date"].append(day)
        columns["devices"].append(devices)
        for pid in PROPERTY_IDS:
            n, mean, lo, hi = stats.get(pid, (None, None, None, None))
            columns[f"{pid}__n"].append(n)
            columns[f"{pid}__mean"].append(mean)
            columns[f"{pid}__min"].append(lo)
            columns[f"{pid}__max"].append(hi)
    table = pa.Table.from_pydict(columns, schema=s)
    dictionary = {"properties": PROPERTIES, "version": 1}
    table = table.replace_schema_metadata({
        b"feed": b"openaq-aggregates",
        b"dataset": b"cells",
        b"window": b"1d",
        b"h3_res": str(RES).encode(),
        b"generation": GENERATION.encode(),
        b"crowd_rule": b"none, public licensed stations",
        b"dictionary": json.dumps(dictionary, ensure_ascii=False).encode(),
        b"generated_by": b"tools/discover-fixture/make_openaq_fixture.py",
    })
    path = OUT / f"cells/h3_res={RES}/data.parquet"
    path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(
        table,
        path,
        compression="snappy",
        row_group_size=16384,
        use_dictionary=True,
        write_statistics=True,
        write_page_index=False,
        data_page_version="1.0",
    )
    manifest = {
        "generation": GENERATION,
        "derive_version": "openaq-aggregates/v1",
        "as_of": AS_OF,
        "stations": 6,
        "outputs": [{"file": f"cells/h3_res={RES}/data.parquet", "bytes": path.stat().st_size, "rows": len(rows)}],
        "cell_properties": PROPERTIES,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"{path.relative_to(REPO)}  rows={len(rows)}  bytes={path.stat().st_size}")
    for name, cell in CELLS.items():
        print(f"{name} {h3.str_to_int(cell)}")


if __name__ == "__main__":
    main()
