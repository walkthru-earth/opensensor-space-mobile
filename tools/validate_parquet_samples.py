#!/usr/bin/env python3
"""Validate files written by the app's Kotlin ParquetWriter with independent readers.

    cd ../aq-parquet && pixi run python ../opensensor-space-android/tools/validate_parquet_samples.py [paths…]

Without arguments it checks `app/build/parquet-samples/*.parquet` (written by `ParquetWriterTest`; run
`./gradlew testDebugUnitTest` first), `core/parquet/build/geoparquet-samples/*.parquet` (written by
`GeoParquetFileTest`) and every derived daily file it can find under a device's `derived/v1/` copy
passed on the command line. For each file: PyArrow reads every row group and column, DuckDB reads the
whole table and a statistics-pruned range query, and the footer statistics are compared against the
values (min/max over non-NaN values, null_count, nan_count). A file with a GEOMETRY column is also
checked as GeoParquet 2.0: `geo` key (version 2.0.0, WKB, primary column), the logical type on the
column, per-chunk geospatial statistics equal to the bbox/types PyArrow derives from the WKB, the
`geo` bbox equal to their union, and DuckDB `ST_AsText` on every row. Exit code 1 on any mismatch.
"""
from __future__ import annotations

import json
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
            if c.geo_statistics is not None or str(pf.schema.column(i).logical_type).startswith("Geometry"):
                check_geometry_chunk(path, g, c, col)
                continue
            values = col.drop_null()
            if pa.types.is_timestamp(values.type):
                values = values.cast(pa.int64())
            py = values.to_pylist()
            finite = [v for v in py if not (isinstance(v, float) and math.isnan(v))]
            if finite:
                if not s.has_min_max:
                    fail(f"{path.name} rg{g} {c.path_in_schema}: has values but no min/max")
                elif c.physical_type == "BYTE_ARRAY":
                    py = [v.encode() if isinstance(v, str) else v for v in py]  # unsigned byte order, as the footer stores it
                    if s.min_raw != min(py) or s.max_raw != max(py):
                        fail(f"{path.name} rg{g} {c.path_in_schema}: string min/max {s.min_raw!r}/{s.max_raw!r} != {min(py)!r}/{max(py)!r}")
                else:
                    fmt = {"INT32": "<i", "INT64": "<q", "FLOAT": "<f", "DOUBLE": "<d"}[c.physical_type]
                    lo = struct.unpack(fmt, s.min_raw)[0] if isinstance(s.min_raw, (bytes, bytearray)) else s.min_raw
                    hi = struct.unpack(fmt, s.max_raw)[0] if isinstance(s.max_raw, (bytes, bytearray)) else s.max_raw
                    if lo != min(finite) or hi != max(finite):
                        fail(f"{path.name} rg{g} {c.path_in_schema}: min/max {lo}/{hi} != {min(finite)}/{max(finite)}")
            elif s.has_min_max:
                fail(f"{path.name} rg{g} {c.path_in_schema}: min/max present without finite values")
        offset += rows
    # Timestamp annotation where expected. Current files store microseconds, older ones nanoseconds.
    units = {"event_time_utc_us": "us", "clock_anchor_utc_us": "us", "event_time_utc_ns": "ns", "clock_anchor_utc_ns": "ns"}
    for name, unit in units.items():
        want = f"timestamp[{unit}, tz=UTC]"
        if name in table.column_names and str(table.schema.field(name).type) != want:
            fail(f"{path.name}: {name} is {table.schema.field(name).type}, expected {want}")
    # DuckDB: full read and a pruned range query.
    con = duckdb.connect()
    n = con.execute("SELECT count(*) FROM read_parquet(?)", [str(path)]).fetchone()[0]
    if n != md.num_rows:
        fail(f"{path.name}: DuckDB count {n} != {md.num_rows}")
    event = next((c for c in ("event_time_utc_us", "event_time_utc_ns") if c in table.column_names), None)
    if event and md.num_rows:
        ts = table.column(event).cast(pa.int64()).drop_null().to_pylist()
        if ts:
            mid = sorted(ts)[len(ts) // 2]
            expect = sum(1 for t in ts if t >= mid)
            epoch = "epoch_us" if event.endswith("_us") else "epoch_ns"
            got = con.execute(f"SELECT count(*) FROM read_parquet(?) WHERE {epoch}({event}) >= ?", [str(path), mid]).fetchone()[0]
            if got != expect:
                fail(f"{path.name}: DuckDB range query {got} != {expect}")
    meta = {k.decode(): v.decode() for k, v in (md.metadata or {}).items()}
    if meta:
        print("   metadata:", {k: meta[k] for k in sorted(meta) if k in ("station_id", "day", "derived_by", "source_count", "coverage_rows", "complete", "purpose")})
    geometry_columns = [pf.schema.column(i).name for i in range(md.num_columns) if str(pf.schema.column(i).logical_type).startswith("Geometry")]
    if geometry_columns:
        check_geoparquet(path, pf, table, meta, geometry_columns, con)


def geo_stats_of(wkb_values: list) -> tuple[dict, list[int]]:
    """PyArrow's own view of a WKB column's statistics: write it to a scratch file and read the footer back."""
    import io

    class Wkb(pa.ExtensionType):
        def __init__(self):
            super().__init__(pa.binary(), "geoarrow.wkb")

        def __arrow_ext_serialize__(self):
            return b"{}"

        @classmethod
        def __arrow_ext_deserialize__(cls, storage_type, serialized):
            return cls()

    try:
        pa.register_extension_type(Wkb())
    except pa.ArrowKeyError:
        pass
    arr = pa.ExtensionArray.from_storage(Wkb(), pa.array(wkb_values, pa.binary()))
    buf = io.BytesIO()
    pq.write_table(pa.table({"g": arr}), buf)
    buf.seek(0)
    gs = pq.ParquetFile(buf).metadata.row_group(0).column(0).geo_statistics
    box = {k: getattr(gs, k) for k in ("xmin", "xmax", "ymin", "ymax", "zmin", "zmax", "mmin", "mmax")}
    return box, sorted(gs.geospatial_types)


def check_geometry_chunk(path: Path, g: int, c, col: pa.ChunkedArray) -> None:
    """Per-chunk geospatial statistics must equal what PyArrow derives from the same WKB."""
    name = c.path_in_schema
    if c.statistics is not None and c.statistics.has_min_max:
        fail(f"{path.name} rg{g} {name}: GEOMETRY chunk carries byte-wise min/max (undefined order)")
    storage = col.combine_chunks()
    if isinstance(storage, pa.ExtensionArray):
        storage = storage.storage
    values = storage.to_pylist()
    if all(v is None for v in values):
        if c.geo_statistics is not None:
            fail(f"{path.name} rg{g} {name}: geospatial statistics on an all-null chunk")
        return
    if c.geo_statistics is None:
        fail(f"{path.name} rg{g} {name}: no geospatial statistics")
        return
    box, types = geo_stats_of(values)
    ours = {k: getattr(c.geo_statistics, k) for k in box}
    if ours != box:
        fail(f"{path.name} rg{g} {name}: bbox {ours} != PyArrow {box}")
    if sorted(c.geo_statistics.geospatial_types) != types:
        fail(f"{path.name} rg{g} {name}: types {sorted(c.geo_statistics.geospatial_types)} != PyArrow {types}")


def check_geoparquet(path: Path, pf: pq.ParquetFile, table: pa.Table, meta: dict, geometry_columns: list[str], con: duckdb.DuckDBPyConnection) -> None:
    md = pf.metadata
    geo = json.loads(meta["geo"]) if "geo" in meta else None
    if geo is None:
        fail(f"{path.name}: GEOMETRY column without a `geo` key")
        return
    if geo.get("version") != "2.0.0":
        fail(f"{path.name}: geo.version {geo.get('version')!r} != '2.0.0'")
    if geo.get("primary_column") not in geometry_columns:
        fail(f"{path.name}: geo.primary_column {geo.get('primary_column')!r} is not a GEOMETRY column")
    for name in geometry_columns:
        col = geo.get("columns", {}).get(name)
        if col is None:
            fail(f"{path.name}: geo.columns lacks {name!r}")
            continue
        if col.get("encoding") != "WKB":
            fail(f"{path.name}: {name} encoding {col.get('encoding')!r} != 'WKB'")
        idx = table.schema.get_field_index(name)
        # File bbox = union of the chunk bboxes; geometry_types = union of the chunk type codes.
        boxes = [md.row_group(g).column(idx).geo_statistics for g in range(md.num_row_groups)]
        boxes = [b for b in boxes if b is not None]
        if boxes and "bbox" in col:
            xmin, xmax = min(b.xmin for b in boxes), max(b.xmax for b in boxes)
            ymin, ymax = min(b.ymin for b in boxes), max(b.ymax for b in boxes)
            zs = [b for b in boxes if b.zmin is not None]
            ms = [b for b in boxes if b.mmin is not None]
            expect = [xmin, ymin] + ([min(b.zmin for b in zs)] if zs else []) + ([min(b.mmin for b in ms)] if ms and zs else []) + [xmax, ymax] + ([max(b.zmax for b in zs)] if zs else []) + ([max(b.mmax for b in ms)] if ms and zs else [])
            if col["bbox"] != expect:
                fail(f"{path.name}: {name} geo.bbox {col['bbox']} != union of chunk statistics {expect}")
        codes = sorted({t for b in boxes for t in b.geospatial_types})
        names = {1: "Point", 2: "LineString", 3: "Polygon", 4: "MultiPoint", 5: "MultiLineString", 6: "MultiPolygon", 7: "GeometryCollection"}
        expect_types = sorted(names[c % 1000] + {0: "", 1000: " Z", 2000: " M", 3000: " ZM"}[c - c % 1000] for c in codes)
        if sorted(col.get("geometry_types", [])) != expect_types:
            fail(f"{path.name}: {name} geometry_types {sorted(col.get('geometry_types', []))} != {expect_types}")
        # DuckDB must parse every WKB.
        con.execute("LOAD spatial")
        bad = con.execute(f"SELECT count(*) FROM read_parquet(?) WHERE {name} IS NOT NULL AND ST_AsText({name}) IS NULL", [str(path)]).fetchone()[0]
        if bad:
            fail(f"{path.name}: DuckDB could not read {bad} geometries of {name}")
        n = con.execute(f"SELECT count({name}) FROM read_parquet(?)", [str(path)]).fetchone()[0]
        print(f"   geoparquet {geo['version']}: {name} {n} geometries, types {col.get('geometry_types')}, bbox {col.get('bbox')}")


def main() -> None:
    args = [Path(a) for a in sys.argv[1:]]
    paths: list[Path] = []
    for a in args or [ANDROID / "app/build/parquet-samples", ANDROID / "core/parquet/build/geoparquet-samples"]:
        paths += sorted(a.rglob("*.parquet")) if a.is_dir() else [a]
    if not paths:
        sys.exit("no files to validate (run ./gradlew verify first)")
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
