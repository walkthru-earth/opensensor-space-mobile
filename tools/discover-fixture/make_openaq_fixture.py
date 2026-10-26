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

It also writes `openaq-aggregates-republished/cells/h3_res=5/data.parquet`, the same as_of under
generation `fixture-0002` with Cairo's PM2.5 mean on 10-08 at 25.0, for the test of a file replaced
in place.

And `openaq-aggregates-stac/`, a feed described by a STAC Collection `collection.json` (the discover
Collection, docs/devices-and-discovery.md) instead of a manifest. Its cells file has PM2.5 and a soil
property. The Collection gives PM2.5 the WHO 2021 scale and the soil property a title and no scale,
so the app shows it on the neutral ramp.
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
REPUBLISHED = REPO / "core/publicfeed/src/commonTest/fixtures/openaq-aggregates-republished"
REPUBLISHED_GENERATION = "fixture-0002"
STAC = REPO / "core/publicfeed/src/commonTest/fixtures/openaq-aggregates-stac"
STAC_UPDATED = "2026-10-09T03:00:00Z"

PROPERTIES = [
    {"id": "pm25", "label": "PM2.5", "unit": "µg/m³", "category": "Air", "precision": 0.1},
    {"id": "pm10", "label": "PM10", "unit": "µg/m³", "category": "Air", "precision": 0.1},
    {"id": "pm1", "label": "PM1", "unit": "µg/m³", "category": "Air", "precision": 0.1},
    {"id": "no2", "label": "NO2", "unit": "ppm", "category": "Air", "precision": 0.001},
    {"id": "temperature", "label": "Temperature", "unit": "°C", "category": "Weather", "precision": 0.1},
    {"id": "humidity", "label": "Humidity", "unit": "%", "category": "Weather", "precision": 1},
    {"id": "um003", "label": "PM0.3 count", "unit": "particles/cm³", "category": "Air", "precision": 1},
]

STAC_PROPERTIES = [
    {"id": "pm25", "label": "PM2.5", "unit": "µg/m³", "category": "Air", "precision": 0.1},
    {"id": "soil_moisture", "label": "soil_moisture", "unit": "m³/m³", "category": "Soil", "precision": 0.01},
]

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

# The STAC fixture rows. PM2.5 in Cairo, soil moisture in Cairo, Lagos and Nairobi.
STAC_ROWS = [
    ("cairo", 8, 2, {"pm25": (2, 20.0, 12.0, 31.0), "soil_moisture": (2, 0.31, 0.27, 0.35)}),
    ("lagos", 8, 1, {"soil_moisture": (1, 0.25, 0.22, 0.29)}),
    ("nairobi", 7, 1, {"soil_moisture": (1, 0.18, 0.15, 0.2)}),
]

WHO_URL = "https://www.who.int/publications/i/item/9789240034228"


def stac_collection() -> dict:
    """The discover Collection of the STAC fixture. One asset per h3_res, columns per property."""
    columns = [{"name": "h3_index", "type": "int64", "title": "H3 cell"}, {"name": "date", "type": "date32", "title": "Station local day"}]
    for p in STAC_PROPERTIES:
        title = "Soil moisture" if p["id"] == "soil_moisture" else p["label"]
        columns.append({"name": f"{p['id']}__n", "type": "int32", "title": f"{title} readings"})
        mean = {"name": f"{p['id']}__mean", "type": "double", "title": title, "unit": p["unit"]}
        if p["id"] == "pm25":
            mean["opensensor:scale"] = "who-2021-pm25"
        columns.append(mean)
        columns += [{"name": f"{p['id']}__{s}", "type": "double", "unit": p["unit"]} for s in ("min", "max")]
    return {
        "type": "Collection",
        "stac_version": "1.1.0",
        "stac_extensions": ["https://stac-extensions.github.io/table/v1.3.0/schema.json"],
        "id": "discover-cells",
        "title": "Discover cells",
        "description": "Daily H3 aggregates of public stations. Fixture, every number is made up.",
        "license": "CC-BY-4.0",
        "updated": STAC_UPDATED,
        "extent": {
            "spatial": {"bbox": [[-180, -90, 180, 90]]},
            "temporal": {"interval": [["2026-10-02T00:00:00Z", f"{AS_OF}T23:59:59Z"]]},
        },
        "opensensor:scales": {
            "who-2021-pm25": {
                "source": WHO_URL,
                "unit": "µg/m³",
                "guideline": 15,
                "steps": [
                    {"upper": 15, "label": {"en": "Within the WHO guideline"}, "color_hint": "C6EBA8"},
                    {"upper": 25, "label": {"en": "Within WHO interim target 4"}, "color_hint": "F5D75E"},
                    {"upper": 37.5, "label": {"en": "Within WHO interim target 3"}, "color_hint": "F2A14A"},
                    {"upper": 50, "label": {"en": "Within WHO interim target 2"}, "color_hint": "DC5A4A"},
                    {"upper": 75, "label": {"en": "Within WHO interim target 1"}, "color_hint": "9A3F8E"},
                    {"upper": None, "label": {"en": "Above every WHO target"}, "color_hint": "5C1A33"},
                ],
            }
        },
        "item_assets": {"data": {"type": "application/vnd.apache.parquet", "roles": ["data"], "table:columns": columns}},
        "assets": {f"h3_res={RES}": {"href": f"cells/h3_res={RES}/data.parquet", "type": "application/vnd.apache.parquet", "roles": ["data"]}},
        "links": [],
    }


def schema(property_ids: list[str]) -> pa.Schema:
    fields = [pa.field("h3_index", pa.int64()), pa.field("date", pa.date32()), pa.field("devices", pa.int32())]
    for pid in property_ids:
        fields.append(pa.field(f"{pid}__n", pa.int32()))
        fields += [pa.field(f"{pid}__{s}", pa.float64()) for s in ("mean", "min", "max")]
    return pa.schema(fields)


def write_cells(source: list, generation: str, out: Path, properties: list = PROPERTIES) -> tuple[Path, int]:
    """[source] rows as the cells file under [out], with [generation] in the key value metadata."""
    property_ids = [p["id"] for p in properties]
    s = schema(property_ids)
    columns: dict[str, list] = {f.name: [] for f in s}
    rows = []
    for cell, day, devices, stats in source:
        month, dom = day if isinstance(day, tuple) else (10, day)
        rows.append((h3.str_to_int(CELLS[cell]), date(2026, month, dom), devices, stats))
    rows.sort(key=lambda r: (r[0], r[1]))
    for index, day, devices, stats in rows:
        columns["h3_index"].append(index)
        columns["date"].append(day)
        columns["devices"].append(devices)
        for pid in property_ids:
            n, mean, lo, hi = stats.get(pid, (None, None, None, None))
            columns[f"{pid}__n"].append(n)
            columns[f"{pid}__mean"].append(mean)
            columns[f"{pid}__min"].append(lo)
            columns[f"{pid}__max"].append(hi)
    table = pa.Table.from_pydict(columns, schema=s)
    dictionary = {"properties": properties, "version": 1}
    table = table.replace_schema_metadata({
        b"feed": b"openaq-aggregates",
        b"dataset": b"cells",
        b"window": b"1d",
        b"h3_res": str(RES).encode(),
        b"generation": generation.encode(),
        b"crowd_rule": b"none, public licensed stations",
        b"dictionary": json.dumps(dictionary, ensure_ascii=False).encode(),
        b"generated_by": b"tools/discover-fixture/make_openaq_fixture.py",
    })
    path = out / f"cells/h3_res={RES}/data.parquet"
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
    return path, len(rows)


def main() -> None:
    path, rows = write_cells(ROWS, GENERATION, OUT)
    manifest = {
        "generation": GENERATION,
        "derive_version": "openaq-aggregates/v1",
        "as_of": AS_OF,
        "stations": 6,
        "outputs": [{"file": f"cells/h3_res={RES}/data.parquet", "bytes": path.stat().st_size, "rows": rows}],
        "cell_properties": PROPERTIES,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"{path.relative_to(REPO)}  rows={rows}  bytes={path.stat().st_size}")
    republished = [(c, d, n, {**s, "pm25": (2, 25.0, 12.0, 31.0)} if (c, d) == ("cairo", 8) else s) for c, d, n, s in ROWS]
    path, rows = write_cells(republished, REPUBLISHED_GENERATION, REPUBLISHED)
    print(f"{path.relative_to(REPO)}  rows={rows}  bytes={path.stat().st_size}")
    path, rows = write_cells(STAC_ROWS, STAC_UPDATED, STAC, STAC_PROPERTIES)
    (STAC / "collection.json").write_text(json.dumps(stac_collection(), ensure_ascii=False, indent=2) + "\n")
    print(f"{path.relative_to(REPO)}  rows={rows}  bytes={path.stat().st_size}")
    for name, cell in CELLS.items():
        print(f"{name} {h3.str_to_int(cell)}")


if __name__ == "__main__":
    main()
