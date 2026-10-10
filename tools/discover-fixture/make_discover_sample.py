#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["h3==4.5.0", "pyarrow==25.0.1"]
# ///
"""Write the bundled Discover sample feed in the live public feed layout.

    uv run tools/discover-fixture/make_discover_sample.py

The layout and schema follow docs/devices-and-discovery.md, "Public feed contract":

    <out>/v1/cells/window={1h|24h|7d}/date=YYYY-MM-DD/hour=HH/h3_res={2..7}/data.parquet

- One plain Parquet file per H3 resolution 2 to 7. Never finer than 7.
- `h3_index` INT64, sorted ascending. `devices` INT32, always 3 or more (crowd rule).
  `updated` TIMESTAMP(MILLIS, UTC). Flat metric columns `<property>__n` (INT32) and
  `__mean`, `__min`, `__max` (DOUBLE, null when the cell does not measure the property).
- Hourly means only. Values are rounded to each property's precision.
- SNAPPY compression (never ZSTD), PLAIN or RLE_DICTIONARY encodings, statistics on every
  column. One row group per file, sorted by `h3_index` (declared in `sorting_columns`), cut
  into small pages of about PAGE_ROWS rows with a page index, so a viewport reads only the
  pages whose `h3_index` range it covers, as the live feed will.
- Key value metadata `sample = true` and `dictionary` (property id, label, unit, category,
  precision), so the app reads labels and units from the file, never from code.

Every number is made up. The output is deterministic (fixed seed and fixed partition hour), so
running the script again produces byte-identical files.
"""
from __future__ import annotations

import json
import math
import random
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path

import h3
import pyarrow as pa
import pyarrow.parquet as pq

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "feature/discover/src/commonMain/composeResources/files/discover-sample"

PARTITION = datetime(2026, 10, 8, 10, tzinfo=timezone.utc)  # the last complete hour
HOURS = 7 * 24
FINEST = 7
COARSEST = 2
PAGE_ROWS = 16
WINDOWS = {"1h": 1, "24h": 24, "7d": HOURS}

# Property dictionary. Precision follows the privacy rule on rounding (PM to 1 µg/m³,
# temperature to 0.5 °C, humidity to 1 percent).
PROPERTIES = [
    {"id": "pm25", "label": "PM2.5", "unit": "µg/m³", "category": "Air", "precision": 1.0},
    {"id": "pm10", "label": "PM10", "unit": "µg/m³", "category": "Air", "precision": 1.0},
    {"id": "temperature", "label": "Temperature", "unit": "°C", "category": "Weather", "precision": 0.5},
    {"id": "humidity", "label": "Humidity", "unit": "%", "category": "Weather", "precision": 1.0},
    {"id": "co2", "label": "CO2", "unit": "ppm", "category": "Air", "precision": 10.0},
]
PROPERTY_IDS = [p["id"] for p in PROPERTIES]

# (name, lat, lng, properties measured, base values, cells, stale share)
CITIES = [
    ("Berlin", 52.520, 13.405, ["pm25", "pm10", "temperature", "humidity"], {"pm25": 11, "pm10": 18, "temperature": 12, "humidity": 70}, 34, 0.0),
    ("Cairo", 30.044, 31.236, ["pm25", "pm10", "temperature", "humidity"], {"pm25": 38, "pm10": 85, "temperature": 27, "humidity": 48}, 40, 0.0),
    ("Lisbon", 38.722, -9.139, ["temperature", "humidity"], {"temperature": 19, "humidity": 66}, 26, 0.0),
    ("Nairobi", -1.286, 36.817, ["pm25", "co2", "temperature"], {"pm25": 22, "co2": 460, "temperature": 21}, 24, 0.25),
    ("New York", 40.713, -74.006, ["pm25", "temperature", "humidity", "co2"], {"pm25": 9, "temperature": 15, "humidity": 58, "co2": 430}, 36, 0.0),
    ("Jakarta", -6.208, 106.846, ["pm25", "pm10", "humidity"], {"pm25": 45, "pm10": 70, "humidity": 78}, 30, 0.0),
]

# Daily swing and noise per property, as a fraction of the base value.
SWING = {"pm25": 0.35, "pm10": 0.3, "temperature": 0.25, "humidity": 0.15, "co2": 0.08}
NOISE = {"pm25": 0.25, "pm10": 0.25, "temperature": 0.06, "humidity": 0.08, "co2": 0.05}


def round_to(value: float, step: float) -> float:
    return round(round(value / step) * step, 6)


def city_cells(rng: random.Random, lat: float, lng: float, count: int) -> list[str]:
    centre = h3.latlng_to_cell(lat, lng, FINEST)
    disk = sorted(h3.grid_disk(centre, 5))
    rng.shuffle(disk)
    # Keep the centre and a compact neighbourhood, so parents hold several children.
    disk.sort(key=lambda c: h3.grid_distance(centre, c) + rng.random() * 2.5)
    return disk[:count]


def simulate() -> dict[str, dict]:
    """Per resolution 7 cell, device hourly series per property: {cell: {prop: [[v]*HOURS]*devices}}."""
    rng = random.Random(20261008)
    cells: dict[str, dict] = {}
    for name, lat, lng, props, base, count, stale_share in CITIES:
        tz_offset = lng / 15.0
        for cell in city_cells(rng, lat, lng, count):
            devices = rng.randint(3, 9)
            stale = rng.random() < stale_share
            missing_tail = 5 if stale else 0
            local = 1.0 + rng.uniform(-0.2, 0.2)
            series: dict[str, list[list[float | None]]] = {}
            for prop in props:
                rows = []
                for _ in range(devices):
                    device_bias = rng.uniform(-0.1, 0.1)
                    values: list[float | None] = []
                    for h in range(HOURS):
                        if h >= HOURS - missing_tail:
                            values.append(None)
                            continue
                        hour_utc = (PARTITION - timedelta(hours=HOURS - 1 - h)).hour
                        local_hour = (hour_utc + tz_offset) % 24
                        diurnal = math.sin((local_hour - 9) / 24 * 2 * math.pi)
                        b = base[prop]
                        v = b * local * (1 + device_bias + SWING[prop] * diurnal + rng.gauss(0, NOISE[prop]))
                        if prop == "humidity":
                            v = min(max(v, 5.0), 100.0)
                        elif prop != "temperature":
                            v = max(v, 0.0)
                        values.append(v)
                    rows.append(values)
                series[prop] = rows
            cells[cell] = {"devices": devices, "series": series, "last_hour": HOURS - 1 - missing_tail}
    return cells


def aggregate(children: list[dict], window_hours: int) -> dict | None:
    devices = sum(c["devices"] for c in children)
    if devices < 3:
        return None  # crowd rule
    start = HOURS - window_hours
    last_hour = max(c["last_hour"] for c in children)
    if last_hour < start:
        return None  # nothing in this window
    row: dict = {"devices": devices, "updated": PARTITION - timedelta(hours=HOURS - 1 - last_hour) + timedelta(hours=1)}
    for p in PROPERTIES:
        pid, step = p["id"], p["precision"]
        values = [
            v
            for c in children
            for device in c["series"].get(pid, [])
            for v in device[start:]
            if v is not None
        ]
        if values:
            row[f"{pid}__n"] = len(values)
            row[f"{pid}__mean"] = round_to(sum(values) / len(values), step)
            row[f"{pid}__min"] = round_to(min(values), step)
            row[f"{pid}__max"] = round_to(max(values), step)
        else:
            row[f"{pid}__n"] = None
            row[f"{pid}__mean"] = row[f"{pid}__min"] = row[f"{pid}__max"] = None
    return row


def schema() -> pa.Schema:
    fields = [
        pa.field("h3_index", pa.int64(), nullable=False),
        pa.field("devices", pa.int32(), nullable=False),
        pa.field("updated", pa.timestamp("ms", tz="UTC"), nullable=False),
    ]
    for pid in PROPERTY_IDS:
        fields.append(pa.field(f"{pid}__n", pa.int32()))
        fields += [pa.field(f"{pid}__{s}", pa.float64()) for s in ("mean", "min", "max")]
    return pa.schema(fields)


def write(rows: list[tuple[int, dict]], path: Path, window: str, res: int) -> None:
    s = schema()
    columns: dict[str, list] = {f.name: [] for f in s}
    for index, row in rows:
        columns["h3_index"].append(index)
        for name in s.names[1:]:
            columns[name].append(row[name])
    table = pa.Table.from_pydict(columns, schema=s)
    metadata = {
        b"sample": b"true",
        b"feed": b"opensensor/discover/v1/cells",
        b"window": window.encode(),
        b"h3_res": str(res).encode(),
        b"dictionary": json.dumps({"version": 1, "properties": PROPERTIES}, ensure_ascii=False).encode(),
        b"license": b"CC-BY-4.0",
        b"generated_by": b"tools/discover-fixture/make_discover_sample.py",
    }
    table = table.replace_schema_metadata(metadata)
    path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(
        table,
        path,
        compression="snappy",
        row_group_size=len(rows),
        # A page closes once it holds data_page_size bytes, checked every write_batch_size rows,
        # so a tiny page size gives pages of exactly PAGE_ROWS rows.
        data_page_size=1,
        write_batch_size=PAGE_ROWS,
        sorting_columns=[pq.SortingColumn(0)],
        use_dictionary=False,
        write_statistics=True,
        write_page_index=True,
        data_page_version="1.0",
    )


def main() -> None:
    cells = simulate()
    if OUT.exists():
        shutil.rmtree(OUT)
    date = PARTITION.strftime("%Y-%m-%d")
    hour = PARTITION.strftime("%H")
    total = 0
    for window, hours in WINDOWS.items():
        for res in range(COARSEST, FINEST + 1):
            groups: dict[str, list[dict]] = {}
            for cell, data in cells.items():
                groups.setdefault(h3.cell_to_parent(cell, res), []).append(data)
            rows = []
            for parent, children in groups.items():
                row = aggregate(children, hours)
                if row is not None:
                    rows.append((h3.str_to_int(parent), row))
            rows.sort(key=lambda r: r[0])
            path = OUT / f"v1/cells/window={window}/date={date}/hour={hour}/h3_res={res}/data.parquet"
            write(rows, path, window, res)
            total += path.stat().st_size
            print(f"{path.relative_to(REPO)}  rows={len(rows)}  bytes={path.stat().st_size}")
    # The newest partition, as an S3 listing would report it. The bundled source reads this
    # instead of listing a bucket.
    (OUT / "v1/latest.json").write_text(json.dumps({"date": date, "hour": int(hour)}) + "\n")
    print(f"total bytes {total}")


if __name__ == "__main__":
    main()
