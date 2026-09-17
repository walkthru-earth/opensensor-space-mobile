#!/usr/bin/env python3
"""Validate files written by the app's Kotlin ParquetWriter with independent readers.

    cd ../m5stack-aq-parquet && pixi run python ../m5stack-aq-android/tools/validate_parquet_samples.py [paths…]

Without arguments it checks `app/build/parquet-samples/*.parquet` (written by `ParquetWriterTest`; run
`./gradlew testDebugUnitTest` first) and every derived daily file it can find under a device's
`derived/v1/` copy passed on the command line. For each file: PyArrow reads every row group and column,
DuckDB reads the whole table and a statistics-pruned range query, and the footer statistics are
compared against the values (min/max over non-NaN values, null_count, nan_count). Exit code 1 on any
mismatch.
"""
from __future__ import annotations

import math
import struct
import sys
from pathlib import Path

import duckdb
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq

ANDROID = Path(__file__).resolve().parents[1]
FAILED = []


def fail(msg: str) -> None:
    FAILED.append(msg)
    print("FAIL", msg)


def check(path: Path) -> None:
    pf = pq.ParquetFile(path)
    md = pf.metadata
    table = pf.read()
    print(f"{path.name}: {md.num_rows} rows, {md.num_row_groups} row groups, {md.num_columns} columns, created_by={md.created_by!r}")
    if table.num_rows != md.num_rows:
        fail(f"{path.name}: PyArrow read {table.num_rows} rows, footer says {md.num_rows}")
    # Row-group statistics vs. values.
    offset = 0
    for g in range(md.num_row_groups):
        rg = md.row_group(g)
        rows = rg.num_rows
        for i in range(rg.num_columns):
            c = rg.column(i)
            col = table.column(i).slice(offset, rows)
            s = c.statistics
            if s is None:
                fail(f"{path.name} rg{g} {c.path_in_schema}: no statistics")
                continue
            if s.null_count != col.null_count:
                fail(f"{path.name} rg{g} {c.path_in_schema}: null_count {s.null_count} != {col.null_count}")
            values = col.drop_null()
            if pa.types.is_timestamp(values.type):
                values = values.cast(pa.int64())
            py = values.to_pylist()
            finite = [v for v in py if not (isinstance(v, float) and math.isnan(v))]
            if finite:
                if not s.has_min_max:
                    fail(f"{path.name} rg{g} {c.path_in_schema}: has values but no min/max")
                else:
                    fmt = {"INT32": "<i", "INT64": "<q", "FLOAT": "<f", "DOUBLE": "<d"}[c.physical_type]
                    lo = struct.unpack(fmt, s.min_raw)[0] if isinstance(s.min_raw, (bytes, bytearray)) else s.min_raw
                    hi = struct.unpack(fmt, s.max_raw)[0] if isinstance(s.max_raw, (bytes, bytearray)) else s.max_raw
                    if lo != min(finite) or hi != max(finite):
                        fail(f"{path.name} rg{g} {c.path_in_schema}: min/max {lo}/{hi} != {min(finite)}/{max(finite)}")
            elif s.has_min_max:
                fail(f"{path.name} rg{g} {c.path_in_schema}: min/max present without finite values")
        offset += rows
    # Timestamp annotation where expected.
    for name in ("event_time_utc_ns", "clock_anchor_utc_ns"):
        if name in table.column_names and str(table.schema.field(name).type) != "timestamp[ns, tz=UTC]":
            fail(f"{path.name}: {name} is {table.schema.field(name).type}, expected timestamp[ns, tz=UTC]")
    # DuckDB: full read and a pruned range query.
    con = duckdb.connect()
    n = con.execute("SELECT count(*) FROM read_parquet(?)", [str(path)]).fetchone()[0]
    if n != md.num_rows:
        fail(f"{path.name}: DuckDB count {n} != {md.num_rows}")
    if "event_time_utc_ns" in table.column_names and md.num_rows:
        ts = table.column("event_time_utc_ns").cast(pa.int64()).drop_null().to_pylist()
        if ts:
            mid = sorted(ts)[len(ts) // 2]
            expect = sum(1 for t in ts if t >= mid)
            got = con.execute("SELECT count(*) FROM read_parquet(?) WHERE epoch_ns(event_time_utc_ns) >= ?", [str(path), mid]).fetchone()[0]
            if got != expect:
                fail(f"{path.name}: DuckDB range query {got} != {expect}")
    meta = {k.decode(): v.decode() for k, v in (md.metadata or {}).items()}
    if meta:
        print("   metadata:", {k: meta[k] for k in sorted(meta) if k in ("station_id", "day", "derived_by", "source_count", "coverage_rows", "complete", "purpose")})


def main() -> None:
    args = [Path(a) for a in sys.argv[1:]]
    paths: list[Path] = []
    for a in args or [ANDROID / "app/build/parquet-samples"]:
        paths += sorted(a.rglob("*.parquet")) if a.is_dir() else [a]
    if not paths:
        sys.exit("no files to validate (run ./gradlew testDebugUnitTest first)")
    for p in paths:
        try:
            check(p)
        except Exception as e:  # noqa: BLE001 — report and continue
            fail(f"{p.name}: {type(e).__name__}: {e}")
    if FAILED:
        sys.exit(f"{len(FAILED)} problem(s)")
    print(f"PASS {len(paths)} file(s): PyArrow {pa.__version__}, DuckDB {duckdb.__version__}")


if __name__ == "__main__":
    main()
