#!/usr/bin/env python3
"""Build the GeoParquet 2.0 fixtures for the Android reader tests, with PyArrow and DuckDB as oracles.

Run from the firmware repo so its pixi environment (PyArrow >= 21 with the Parquet GEOMETRY type,
DuckDB with the installed `spatial` extension) is used:

    cd ../aq-parquet && pixi run python ../opensensor-space-android/tools/make_geoparquet_fixtures.py

What it writes to `core/parquet/src/test/resources/geoparquet/` (see docs/history-ux-and-data-layer.md §7):

- `pyarrow/`: one table (INT32, INT64, DOUBLE, FLOAT, STRING and a nullable GEOMETRY column holding all
  seven OGC types in XY / XYZ / XYZM, both WKB byte orders, an empty geometry) written by PyArrow with
  every combination of codec {none, snappy, gzip, zstd} × {plain, dictionary} × {data page v1, v2},
  plus a multi-page / multi-row-group variant with a dictionary that overflows into PLAIN pages, plus a
  "native only" file without the `geo` key. The GEOMETRY logical type comes from a registered
  `geoarrow.wkb` extension type; the `geo` metadata is written here (PyArrow leaves that to GeoPandas).
- `duckdb/`: the same rows written by DuckDB `COPY … (FORMAT PARQUET, GEOPARQUET_VERSION 'V2')` and
  `'V1'` (the 1.x form: WKB + `geo`, no logical type) — a second, independent writer.
- `expected.json`: footer facts, per-chunk `geospatial_statistics`, per-column aggregates and, for the
  geometry column, the WKT of every row from DuckDB `ST_AsText`. Nothing here is hand-typed.
"""
from __future__ import annotations

import hashlib
import io
import json
import math
import shutil
import struct
import sys
from pathlib import Path

import duckdb
import pyarrow as pa
import pyarrow.parquet as pq

ANDROID = Path(__file__).resolve().parents[1]
OUT = ANDROID / "core/parquet/src/test/resources/geoparquet"

GEOPARQUET_VERSION = "2.0.0"  # the rc.1 JSON schema pins `version` to the const "2.0.0"


# ----------------------------------------------------------------------------- WKB (ISO variant)

def _coords(dims: int, pts: list[tuple[float, ...]], le: bool) -> bytes:
    e = "<" if le else ">"
    out = b""
    for p in pts:
        assert len(p) == dims, (dims, p)
        out += struct.pack(f"{e}{dims}d", *p)
    return out


def _u32(v: int, le: bool) -> bytes:
    return struct.pack("<I" if le else ">I", v)


def _header(base: int, dims: int, le: bool) -> bytes:
    code = base + {2: 0, 3: 1000, 4: 3000}[dims]  # XY, XYZ, XYZM (XYM = 2000 is not used here)
    return (b"\x01" if le else b"\x00") + _u32(code, le)


def wkb_point(p: tuple[float, ...], le: bool = True) -> bytes:
    return _header(1, len(p), le) + _coords(len(p), [p], le)


def wkb_linestring(pts: list[tuple[float, ...]], dims: int = 2, le: bool = True) -> bytes:
    return _header(2, dims, le) + _u32(len(pts), le) + _coords(dims, pts, le)


def wkb_polygon(rings: list[list[tuple[float, ...]]], dims: int = 2, le: bool = True) -> bytes:
    out = _header(3, dims, le) + _u32(len(rings), le)
    for r in rings:
        out += _u32(len(r), le) + _coords(dims, r, le)
    return out


def wkb_multi(base: int, parts: list[bytes], dims: int = 2, le: bool = True) -> bytes:
    return _header(base, dims, le) + _u32(len(parts), le) + b"".join(parts)


def rows() -> list[dict]:
    """The fixture table: every WKB shape the reader must decode. `geometry` None = null row."""
    sq = [(0.0, 0.0), (4.0, 0.0), (4.0, 4.0), (0.0, 4.0), (0.0, 0.0)]
    hole = [(1.0, 1.0), (1.0, 2.0), (2.0, 2.0), (2.0, 1.0), (1.0, 1.0)]
    return [
        {"id": 1, "name": "point-le", "geometry": wkb_point((10.0, 20.0))},
        {"id": 2, "name": "null", "geometry": None},
        {"id": 3, "name": "point-z-be", "geometry": wkb_point((1.0, 2.0, 3.0), le=False)},
        {"id": 4, "name": "linestring", "geometry": wkb_linestring([(0.0, 0.0), (1.0, 1.0), (2.0, 0.0)])},
        {"id": 5, "name": "polygon-hole", "geometry": wkb_polygon([sq, hole])},
        {"id": 6, "name": "multipoint", "geometry": wkb_multi(4, [wkb_point((-1.0, -1.0)), wkb_point((-2.0, -3.0))])},
        {"id": 7, "name": "multilinestring-be", "geometry": wkb_multi(5, [wkb_linestring([(0.0, 0.0), (1.0, 0.0)], le=False), wkb_linestring([(0.0, 1.0), (1.0, 1.0)], le=False)], le=False)},
        {"id": 8, "name": "multipolygon", "geometry": wkb_multi(6, [wkb_polygon([sq]), wkb_polygon([[(10.0, 10.0), (11.0, 10.0), (11.0, 11.0), (10.0, 10.0)]])])},
        {"id": 9, "name": "collection", "geometry": wkb_multi(7, [wkb_point((5.0, 5.0)), wkb_linestring([(5.0, 5.0), (6.0, 6.0)])])},
        {"id": 10, "name": "point-be", "geometry": wkb_point((-1.5, 2.5), le=False)},
        {"id": 11, "name": "point-zm", "geometry": wkb_point((7.0, 8.0, 9.0, 10.0))},
        {"id": 12, "name": "linestring-empty", "geometry": wkb_linestring([])},
        {"id": 13, "name": "null-2", "geometry": None},
        {"id": 14, "name": "point-le-2", "geometry": wkb_point((10.0, 20.0))},  # duplicate of row 1: exercises the dictionary
    ]


# ----------------------------------------------------------------------------- Arrow table

class WkbType(pa.ExtensionType):
    """`geoarrow.wkb` extension type: makes PyArrow's Parquet writer emit the GEOMETRY logical type."""

    def __init__(self, metadata: bytes = b"{}"):
        self._metadata = metadata
        super().__init__(pa.binary(), "geoarrow.wkb")

    def __arrow_ext_serialize__(self) -> bytes:
        return self._metadata

    @classmethod
    def __arrow_ext_deserialize__(cls, storage_type, serialized):
        return cls(serialized)


pa.register_extension_type(WkbType())


def table() -> pa.Table:
    r = rows()
    n = len(r)
    geometry = pa.ExtensionArray.from_storage(WkbType(), pa.array([x["geometry"] for x in r], pa.binary()))
    return pa.table({
        "id": pa.array([x["id"] for x in r], pa.int32()),
        "big": pa.array([x["id"] * 10_000_000_000 if x["id"] % 5 else None for x in r], pa.int64()),
        "ratio": pa.array([x["id"] / 3.0 if x["id"] % 4 else None for x in r], pa.float64()),
        "temp": pa.array([float(x["id"]) - 0.5 for x in r], pa.float32()),
        "name": pa.array([x["name"] for x in r], pa.string()),
        "geometry": geometry,
    })


def geo_metadata(bbox: list[float], types: list[str]) -> str:
    return json.dumps({
        "version": GEOPARQUET_VERSION,
        "primary_column": "geometry",
        "columns": {"geometry": {"encoding": "WKB", "geometry_types": types, "bbox": bbox}},
    }, separators=(",", ":"))


# ----------------------------------------------------------------------------- oracles

def duckdb_wkt(con: duckdb.DuckDBPyConnection, wkb: bytes | None) -> str | None:
    if wkb is None:
        return None
    return con.execute("SELECT ST_AsText(ST_GeomFromWKB(?::BLOB))", [wkb]).fetchone()[0]


def column_expectation(column: pa.ChunkedArray) -> dict:
    values = column.combine_chunks()
    if pa.types.is_timestamp(values.type):
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
        if finite:
            out["first"], out["last"] = float(py[0]), float(py[-1])
            out["min"], out["max"], out["sum"] = min(finite), max(finite), math.fsum(finite)
    elif pa.types.is_integer(values.type):
        out["first"], out["last"] = int(py[0]), int(py[-1])
        out["min"], out["max"], out["sum"] = min(py), max(py), sum(py)
    elif pa.types.is_string(values.type) or pa.types.is_large_string(values.type):
        out["values"] = values.to_pylist()  # per row, null kept
    return out


def describe(path: Path, rel: str, con: duckdb.DuckDBPyConnection) -> dict:
    pf = pq.ParquetFile(path)
    md = pf.metadata
    schema = pf.schema
    tbl = pf.read()
    columns = []
    for i, name in enumerate(tbl.schema.names):
        col = schema.column(i)
        arrow_type = tbl.schema.field(name).type
        entry = {"name": name, "physical": col.physical_type, "logical": str(col.logical_type), "arrow": str(arrow_type)}
        values = tbl.column(i)
        if col.physical_type == "BYTE_ARRAY" and (str(col.logical_type).startswith("Geometry") or str(col.logical_type).startswith("Geography") or name == "geometry"):
            storage = values.combine_chunks()
            if isinstance(storage, pa.ExtensionArray):
                storage = storage.storage
            entry["nulls"] = storage.null_count
            entry["wkt"] = [duckdb_wkt(con, v) for v in storage.to_pylist()]
        else:
            entry.update(column_expectation(values))
        columns.append(entry)
    row_groups = []
    for g in range(md.num_row_groups):
        rg = md.row_group(g)
        chunks = {}
        for i in range(rg.num_columns):
            c = rg.column(i)
            chunk = {
                "codec": c.compression,
                "encodings": sorted(c.encodings),
                "dictionary_page": bool(c.has_dictionary_page),
                "num_values": c.num_values,
            }
            s = c.statistics
            if s is not None:
                chunk["nulls"] = s.null_count
            gs = c.geo_statistics
            if gs is not None:
                chunk["geo"] = {
                    "types": list(gs.geospatial_types) if gs.geospatial_types is not None else None,
                    "xmin": gs.xmin, "xmax": gs.xmax, "ymin": gs.ymin, "ymax": gs.ymax,
                    "zmin": gs.zmin, "zmax": gs.zmax, "mmin": gs.mmin, "mmax": gs.mmax,
                }
            chunks[c.path_in_schema] = chunk
        row_groups.append({"rows": rg.num_rows, "chunks": chunks})
    kv = {k.decode(): v.decode() for k, v in (md.metadata or {}).items() if k != b"ARROW:schema"}
    return {
        "file": rel,
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "created_by": md.created_by,
        "rows": md.num_rows,
        "columns": len(columns),
        "row_groups": row_groups,
        "metadata": kv,
        "column_details": columns,
    }


# ----------------------------------------------------------------------------- writers

def write_pyarrow(out: Path, tbl: pa.Table, geo: str | None) -> list[tuple[str, Path]]:
    produced = []
    base_md = {"purpose": "geoparquet-fixture"}
    if geo is not None:
        base_md["geo"] = geo
    t = tbl.replace_schema_metadata(base_md)
    for codec in ("none", "snappy", "gzip", "zstd"):
        for dictionary in (False, True):
            for page in ("1.0", "2.0"):
                label = f"pa-{codec}-{'dict' if dictionary else 'plain'}-v{page[0]}"
                path = out / f"{label}.parquet"
                pq.write_table(t, path, compression=codec, use_dictionary=dictionary, data_page_version=page, write_statistics=True)
                produced.append((label, path))
    # Several row groups, several pages per chunk, and a dictionary that overflows into PLAIN pages.
    path = out / "pa-snappy-multipage.parquet"
    pq.write_table(t, path, compression="snappy", use_dictionary=True, data_page_version="1.0",
                   row_group_size=8, max_rows_per_page=3, write_batch_size=3, dictionary_pagesize_limit=150)
    produced.append(("pa-snappy-multipage", path))
    # Native types only: no `geo` key (what a non-GeoParquet-aware Parquet writer emits).
    path = out / "pa-native-only.parquet"
    pq.write_table(tbl.replace_schema_metadata({"purpose": "geoparquet-fixture"}), path, compression="snappy")
    produced.append(("pa-native-only", path))
    return produced


def write_duckdb(out: Path, con: duckdb.DuckDBPyConnection) -> list[tuple[str, Path]]:
    r = rows()
    con.execute("CREATE OR REPLACE TABLE fixture (id INTEGER, name VARCHAR, wkb BLOB)")
    con.executemany("INSERT INTO fixture VALUES (?, ?, ?)", [(x["id"], x["name"], x["geometry"]) for x in r])
    produced = []
    for version in ("V2", "V1"):
        label = f"duckdb-{version.lower()}"
        path = out / f"{label}.parquet"
        # DuckDB 1.5.5 omits the `geo` key as soon as one geometry carries an M value (measured), so the
        # POINT ZM row stays a PyArrow-only case and the DuckDB files are conformant GeoParquet.
        con.execute(f"COPY (SELECT id, name, ST_GeomFromWKB(wkb) AS geometry FROM fixture WHERE name <> 'point-zm' ORDER BY id) TO '{path}' (FORMAT PARQUET, GEOPARQUET_VERSION '{version}')")
        produced.append((label, path))
    return produced


def main() -> None:
    con = duckdb.connect()
    con.execute("LOAD spatial")
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "pyarrow").mkdir(parents=True)
    (OUT / "duckdb").mkdir(parents=True)

    tbl = table()
    # bbox over all geometries, from the oracle rather than by hand.
    buf = io.BytesIO()
    pq.write_table(tbl, buf)
    buf.seek(0)
    gs = pq.ParquetFile(buf).metadata.row_group(0).column(tbl.schema.get_field_index("geometry")).geo_statistics
    bbox = [gs.xmin, gs.ymin, gs.zmin, gs.mmin, gs.xmax, gs.ymax, gs.zmax, gs.mmax]
    types = sorted({
        {1: "Point", 2: "LineString", 3: "Polygon", 4: "MultiPoint", 5: "MultiLineString", 6: "MultiPolygon", 7: "GeometryCollection"}[c % 1000]
        + {0: "", 1000: " Z", 2000: " M", 3000: " ZM"}[c - c % 1000]
        for c in gs.geospatial_types
    })
    geo = geo_metadata(bbox, types)

    entries = []
    for label, path in write_pyarrow(OUT / "pyarrow", tbl, geo):
        entries.append({"label": label, "writer": f"pyarrow {pa.__version__}", **describe(path, f"pyarrow/{path.name}", con)})
    for label, path in write_duckdb(OUT / "duckdb", con):
        entries.append({"label": label, "writer": f"duckdb {duckdb.__version__}", **describe(path, f"duckdb/{path.name}", con)})

    (OUT / "expected.json").write_text(json.dumps({
        "generator": "tools/make_geoparquet_fixtures.py",
        "pyarrow": pa.__version__,
        "duckdb": duckdb.__version__,
        "geoparquet": GEOPARQUET_VERSION,
        "files": entries,
    }, indent=1))
    total = sum(p.stat().st_size for p in OUT.rglob("*.parquet"))
    print(f"{len(entries)} fixtures, {total} bytes of Parquet, expected.json written to {OUT}")


if __name__ == "__main__":
    sys.exit(main())
