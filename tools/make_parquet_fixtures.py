#!/usr/bin/env python3
"""Build the Parquet fixtures for the Android reader tests, with PyArrow as the oracle.

Run from the firmware repo so its pixi environment (PyArrow, clang++) is used:

    cd ../opensensor-space-firmware && pixi run python ../opensensor-space-mobile/tools/make_parquet_fixtures.py

It copies a representative set of *real* device files (v1 73-column, v2 77-column and the first v3 board
file, UNCOMPRESSED
and LZ4_RAW, dated and unsynced, 1 to 90 rows) from the SD export, compiles the firmware's fixture
writers to produce v3-style files (several row groups, statistics, TIMESTAMP annotation), and writes
`expected.json` next to them (in :core:parquet; :core:history and :app tests read the same directory): for every file, the footer facts and per-column aggregates the Kotlin
reader must reproduce exactly. Nothing here is hand-typed.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import shutil
import struct
import subprocess
import sys
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

ANDROID = Path(__file__).resolve().parents[1]
FIRMWARE = ANDROID.parent / "opensensor-space-firmware"
EXPORT = FIRMWARE / "exports/sd-20260916T173205Z/output"
# The first file the board wrote with firmware v6 (contract v3): 2 row groups (90 + 15 rows), statistics,
# TIMESTAMP annotation, fetched over serial on 2026-09-17 (SHA-256 1a06e93b…f3cf).
V3_BOARD = FIRMWARE / "firmware/arduino-m5unified/artifacts/output"
OUT = ANDROID / "core/parquet/src/test/resources/parquet"

THING = "thing=53315f5f-cb85-4d8d-b623-d56266084189"
DEVICE_FILES = [
    # (label, relative path under output/) — one per (columns, codec, dated/unsynced, size class)
    ("v1-lz4-90rows-dated", f"{THING}/year=2026/month=09/day=08/data_1945_e452563e96c72ca057a48924550779b6_92-181-11.parquet"),
    ("v1-lz4-2rows-dated", f"{THING}/year=2026/month=09/day=08/data_2015_e452563e96c72ca057a48924550779b6_348-349-14.parquet"),
    ("v1-lz4-1row-unsynced", f"{THING}/unsynced/boot=e452563e96c72ca057a48924550779b6/data_unsynced_e452563e96c72ca057a48924550779b6_64-64-7.parquet"),
    ("v1-none-90rows-dated", f"{THING}/year=2026/month=09/day=08/data_1900_1491409bed013e1999dc4072ac91ab0f_80-169-6.parquet"),
    ("v1-none-1row-dated", f"{THING}/year=2026/month=09/day=08/data_1830_c838cf99f4a1e867ebec9e2752bad519_3-3-1.parquet"),
    ("v1-none-64rows-unsynced", f"{THING}/unsynced/boot=e452563e96c72ca057a48924550779b6/data_unsynced_e452563e96c72ca057a48924550779b6_0-63-6.parquet"),
    ("v2-lz4-4rows-dated", f"{THING}/year=2026/month=09/day=08/data_2015_763038f2396a5d68927ac324c4a744a2_3-6-1.parquet"),
    ("v2-lz4-1row-dated", f"{THING}/year=2026/month=09/day=08/data_2015_763038f2396a5d68927ac324c4a744a2_7-7-2.parquet"),
    ("v2-none-90rows-unsynced", f"{THING}/unsynced/boot=16201de72e4422d744b04fddf23eec41/data_unsynced_16201de72e4422d744b04fddf23eec41_0-89-0.parquet"),
]


def compile_fixture(sources: list[Path], executable: Path, include: Path | None = None) -> None:
    command = ["clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror"]
    if include:
        command += ["-I", str(include)]
    command += [str(s) for s in sources] + ["-o", str(executable)]
    subprocess.run(command, check=True)


def generate_v3(out: Path) -> list[tuple[str, Path]]:
    bringup = FIRMWARE / "firmware/arduino-m5unified/bringup"
    tmp = out / "_build"
    tmp.mkdir(parents=True, exist_ok=True)
    produced: list[tuple[str, Path]] = []

    contract = tmp / "contract_fixture"
    compile_fixture([FIRMWARE / "tools/telemetry_contract_fixture.cpp", bringup / "parquet_writer.cpp", bringup / "lz4_codec.cpp"], contract)
    for codec in ("none", "lz4"):
        for clock in ("unsynced", "anchored"):
            label = f"v3-contract-{codec}-{clock}"
            path = out / f"{label}.parquet"
            subprocess.run([str(contract), str(path), codec, clock], check=True, stdout=subprocess.DEVNULL)
            produced.append((label, path))

    generic = tmp / "parquet_fixture"
    compile_fixture([FIRMWARE / "tools/parquet_fixture.cpp", bringup / "parquet_writer.cpp", bringup / "lz4_codec.cpp"], generic,
                    include=FIRMWARE / "firmware/arduino-m5unified/vendor/lz4")
    for label, rows, columns, groups in (("hourly", 90, 77, 4), ("max-groups", 3, 9, 8), ("empty", 0, 8, 1), ("one", 1, 8, 1)):
        for codec in ("none", "lz4"):
            name = f"v3-{label}-{codec}"
            path = out / f"{name}.parquet"
            subprocess.run([str(generic), str(path), str(rows), str(columns), codec, str(groups)], check=True, stdout=subprocess.DEVNULL)
            produced.append((name, path))
    shutil.rmtree(tmp)
    return produced


def column_expectation(column: pa.ChunkedArray) -> dict:
    values = column.combine_chunks()
    if pa.types.is_timestamp(values.type):
        # Compare the stored INT64 nanoseconds, not a datetime.
        values = values.cast(pa.int64())
    nulls = values.null_count
    valid = values.drop_null()
    out: dict = {"nulls": nulls, "valid": len(valid)}
    if len(valid) == 0:
        return out
    py = valid.to_pylist()
    if pa.types.is_floating(values.type):
        finite = [v for v in py if math.isfinite(v)]
        out["nan"] = sum(1 for v in py if math.isnan(v))
        out["inf"] = len(py) - len(finite) - out["nan"]
        if finite:
            # Float32 values are exactly representable as doubles; sums are compared with a tolerance.
            # JSON has no ±inf/NaN, so first/last are only recorded when finite; min/max/sum skip them.
            if math.isfinite(py[0]):
                out["first"] = float(py[0])
            if math.isfinite(py[-1]):
                out["last"] = float(py[-1])
            out["min"], out["max"], out["sum"] = min(finite), max(finite), math.fsum(finite)
    else:
        out["first"], out["last"] = int(py[0]), int(py[-1])
        out["min"], out["max"], out["sum"] = min(py), max(py), sum(py)
    return out


def describe(path: Path, rel: str) -> dict:
    pf = pq.ParquetFile(path)
    md = pf.metadata
    schema = pf.schema  # ParquetSchema (leaf-level)
    arrow = pf.schema_arrow
    table = pf.read()
    columns = []
    for i, name in enumerate(arrow.names):
        col = schema.column(i)
        columns.append({
            "name": name,
            "physical": col.physical_type,
            "logical": str(col.logical_type),
            "arrow": str(arrow.field(name).type),
            **column_expectation(table.column(i)),
        })
    row_groups = []
    for g in range(md.num_row_groups):
        rg = md.row_group(g)
        stats = {}
        for i in range(rg.num_columns):
            c = rg.column(i)
            s = c.statistics
            if s is not None and s.has_min_max:
                # Decode the raw PLAIN bytes ourselves so a TIMESTAMP column compares as INT64.
                fmt = {"INT32": "<i", "INT64": "<q", "FLOAT": "<f", "DOUBLE": "<d"}[c.physical_type]

                def raw_value(raw):
                    return struct.unpack(fmt, raw)[0] if isinstance(raw, (bytes, bytearray)) else raw

                lo, hi = raw_value(s.min_raw), raw_value(s.max_raw)
                entry = {"nulls": s.null_count}
                if isinstance(lo, float):
                    # ±inf and NaN are not JSON; the Kotlin test only checks finite bounds.
                    if math.isfinite(lo):
                        entry["min"] = lo
                    if math.isfinite(hi):
                        entry["max"] = hi
                else:
                    entry["min"], entry["max"] = lo, hi
                stats[c.path_in_schema] = entry
        row_groups.append({
            "rows": rg.num_rows,
            "compressed": rg.total_byte_size,
            "codec": rg.column(0).compression if rg.num_columns else None,
            "encodings": sorted(rg.column(0).encodings) if rg.num_columns else [],
            "stats": stats,
        })
    kv = {k.decode(): v.decode() for k, v in (md.metadata or {}).items()}
    return {
        "file": rel,
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "created_by": md.created_by,
        "version": md.format_version,
        "rows": md.num_rows,
        "columns": len(columns),
        "row_groups": row_groups,
        "metadata": kv,
        "column_details": columns,
    }


def main() -> None:
    if not EXPORT.is_dir():
        sys.exit(f"missing SD export at {EXPORT}")
    if OUT.exists():
        shutil.rmtree(OUT)
    device_dir = OUT / "device"
    v3_dir = OUT / "v3"
    device_dir.mkdir(parents=True)
    v3_dir.mkdir(parents=True)

    entries = []
    for label, rel in DEVICE_FILES:
        src = EXPORT / rel
        dst = device_dir / f"{label}.parquet"
        shutil.copyfile(src, dst)
        entries.append({"label": label, "kind": "device", "source": rel, **describe(dst, f"device/{dst.name}")})
    for src in sorted(V3_BOARD.rglob("*.parquet")) if V3_BOARD.is_dir() else []:
        md = pq.ParquetFile(src).metadata
        label = f"v3-board-{md.num_row_groups}rg-{md.num_rows}rows"
        dst = device_dir / f"{label}.parquet"
        shutil.copyfile(src, dst)
        entries.append({"label": label, "kind": "device", "source": str(src.relative_to(FIRMWARE)), **describe(dst, f"device/{dst.name}")})
    for label, path in generate_v3(v3_dir):
        entries.append({"label": label, "kind": "v3-fixture", "source": "firmware fixture writer", **describe(path, f"v3/{path.name}")})

    (OUT / "expected.json").write_text(json.dumps({
        "generator": "tools/make_parquet_fixtures.py",
        "pyarrow": pa.__version__,
        "export": str(EXPORT.relative_to(FIRMWARE.parent)),
        "files": entries,
    }, indent=1, sort_keys=False))
    total = sum(p.stat().st_size for p in OUT.rglob("*.parquet"))
    print(f"{len(entries)} fixtures, {total} bytes of Parquet, expected.json written to {OUT}")


if __name__ == "__main__":
    main()
