#!/usr/bin/env python3
"""Build the DELTA_BINARY_PACKED and BYTE_STREAM_SPLIT fixtures for the reader tests (issue #94).

Run from the firmware repo so its pixi environment (PyArrow, DuckDB) is used:

    cd ../aq-parquet && pixi run python -I ../opensensor-space-mobile/tools/make_encoding_fixtures.py

What it writes to `core/parquet/src/test/resources/parquet/encodings/`:

- `pyarrow-*.parquet`: one seeded table written by PyArrow with DELTA_BINARY_PACKED on the integer
  columns and BYTE_STREAM_SPLIT on the float columns and two integer columns, for every combination of
  codec {none, lz4_raw} and data page {v1, v2}, with small pages and two row groups, plus a page index.
  `pyarrow-one-row` holds a single row, a delta block with no deltas.
- `duckdb-*.parquet`: the same rows written by DuckDB with `PARQUET_VERSION V2`, a second writer that
  picks DELTA_BINARY_PACKED and BYTE_STREAM_SPLIT on its own. DuckDB takes INT32 deltas in 64 bits, so
  a jump across the INT32 range packs 33 bit miniblocks, which PyArrow refuses to read. DuckDB is the
  oracle for its own files, and PyArrow for the rest, each checked against the other where it can read.
- `expected.json`: for every file and column the encodings in the footer, the null count and an FNV-1a
  64 digest of every row as PyArrow decodes it (a 0 byte for a null, else a 1 byte and the 8 little
  endian bytes of the value's bits: an INT32 sign extended, a FLOAT's 32 bits zero extended). The
  Kotlin test computes the same digest, so a match is bit for bit. Nothing here is hand typed.
"""
from __future__ import annotations

import json
import random
import shutil
import struct
import sys
from pathlib import Path

import duckdb
import pyarrow as pa
import pyarrow.parquet as pq

MOBILE = Path(__file__).resolve().parents[1]
OUT = MOBILE / "core/parquet/src/test/resources/parquet/encodings"

ROWS = 500
ROW_GROUP = 300
INT32_MIN, INT32_MAX = -(2**31), 2**31 - 1
INT64_MIN, INT64_MAX = -(2**63), 2**63 - 1

DELTA = "DELTA_BINARY_PACKED"
SPLIT = "BYTE_STREAM_SPLIT"
PYARROW_ENCODINGS = {
    "seq": DELTA,
    "i32_delta": DELTA,
    "i32_delta_null": DELTA,
    "i64_delta": DELTA,
    "f32_split": SPLIT,
    "f64_split": SPLIT,
    "i32_split": SPLIT,
    "i64_split": SPLIT,
}
# DuckDB chooses its own encodings under PARQUET_VERSION V2. These are the ones it must pick.
DUCKDB_ENCODINGS = {"seq": DELTA, "i32_delta": DELTA, "i64_delta": DELTA, "f32_split": SPLIT, "f64_split": SPLIT}

FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MASK64 = (1 << 64) - 1


def table(rows: int) -> pa.Table:
    """Seeded rows: a sorted counter with wide deltas (so its pages are small enough to prune), random walks with sign changes, the integer extremes, nulls, and
    the float edge cases (NaN, both infinities, negative zero, subnormals)."""
    rng = random.Random(94)
    seq, i32, i32n, i64, f32, f64, i32s, i64s = [], [], [], [], [], [], [], []
    walk32, walk64, counter = 0, 1_760_000_000_000_000, 1_000
    specials32 = [float("nan"), float("inf"), float("-inf"), -0.0, 0.0, 1.0e-45, 3.4028235e38]
    specials64 = [float("nan"), float("inf"), float("-inf"), -0.0, 0.0, 5.0e-324, 1.7976931348623157e308]
    for i in range(rows):
        counter += rng.randint(1, 2**40)
        seq.append(counter)
        walk32 += rng.randint(-50_000, 50_000)
        i32.append([INT32_MIN, INT32_MAX][i % 2] if i % 97 == 13 else max(INT32_MIN, min(INT32_MAX, walk32)))
        i32n.append(None if rng.random() < 0.2 else rng.randint(-5, 5) if i < 200 else rng.randint(INT32_MIN, INT32_MAX))
        walk64 += rng.randint(0, 2_000_000)
        i64.append([INT64_MIN, INT64_MAX][i % 2] if i % 89 == 7 else walk64)
        f32.append(None if rng.random() < 0.1 else specials32[i % 7] if i % 31 == 5 else rng.uniform(-40.0, 60.0))
        f64.append(specials64[i % 7] if i % 29 == 3 else rng.gauss(400.0, 120.0))
        i32s.append(rng.randint(INT32_MIN, INT32_MAX))
        i64s.append(rng.randint(INT64_MIN, INT64_MAX))
    return pa.table({
        "seq": pa.array(seq, pa.int64()),
        "i32_delta": pa.array(i32, pa.int32()),
        "i32_delta_null": pa.array(i32n, pa.int32()),
        "i64_delta": pa.array(i64, pa.int64()),
        "f32_split": pa.array(f32, pa.float32()),
        "f64_split": pa.array(f64, pa.float64()),
        "i32_split": pa.array(i32s, pa.int32()),
        "i64_split": pa.array(i64s, pa.int64()),
    }).replace_schema_metadata(None)


def write_pyarrow(t: pa.Table, path: Path, codec: str, page_version: str) -> None:
    pq.write_table(
        t,
        path,
        use_dictionary=False,
        column_encoding=PYARROW_ENCODINGS,
        compression=codec,
        data_page_version=page_version,
        data_page_size=256,
        write_batch_size=32,
        row_group_size=ROW_GROUP,
        write_page_index=True,
        store_schema=False,
    )


def write_duckdb(t: pa.Table, path: Path, codec: str) -> None:
    con = duckdb.connect()
    con.register("t", t)
    con.execute(f"COPY (SELECT * FROM t) TO '{path}' (FORMAT parquet, PARQUET_VERSION V2, COMPRESSION {codec}, ROW_GROUP_SIZE {ROW_GROUP})")
    con.close()


def bits(value, type_: pa.DataType) -> int:
    if pa.types.is_int32(type_) or pa.types.is_int64(type_):
        return value & MASK64
    if pa.types.is_float32(type_):
        return struct.unpack("<I", struct.pack("<f", value))[0]
    return struct.unpack("<Q", struct.pack("<d", value))[0]


def raw_values(chunk: pa.Array) -> list[int | None]:
    """The bits of every slot, read from the Arrow buffer so a NaN keeps its payload."""
    width = chunk.type.byte_width
    fmt = {(4, False): "<i", (8, False): "<q", (4, True): "<I", (8, True): "<Q"}[(width, pa.types.is_floating(chunk.type))]
    data = chunk.buffers()[1]
    out: list[int | None] = []
    for i in range(len(chunk)):
        if not chunk[i].is_valid:
            out.append(None)
            continue
        (v,) = struct.unpack_from(fmt, data, (chunk.offset + i) * width)
        out.append(v & MASK64 if not pa.types.is_floating(chunk.type) else v)
    return out


def digest(column: pa.ChunkedArray) -> tuple[int, str]:
    h = FNV_OFFSET
    nulls = 0
    for chunk in column.chunks:
        for v in raw_values(chunk):
            if v is None:
                nulls += 1
                h = ((h ^ 0) * FNV_PRIME) & MASK64
                continue
            for b in b"\x01" + struct.pack("<Q", v):
                h = ((h ^ b) * FNV_PRIME) & MASK64
    return nulls, f"{h:016x}"


def decode_duckdb(path: Path, name: str) -> pa.ChunkedArray | None:
    """[name] as DuckDB decodes it, None when DuckDB cannot (it reads BYTE_STREAM_SPLIT on floats only)."""
    con = duckdb.connect()
    try:
        return con.execute(f'SELECT "{name}" FROM read_parquet(\'{path}\')').to_arrow_table().column(0)
    except duckdb.Error:
        return None
    finally:
        con.close()


def describe(path: Path, label: str, required: dict[str, str], oracle: str) -> dict:
    """[oracle] decodes every column, and the other reader must agree wherever it can read the column."""
    pf = pq.ParquetFile(path)
    md = pf.metadata
    columns = {}
    for i, name in enumerate(md.schema.names):
        encodings = sorted({e for g in range(md.num_row_groups) for e in md.row_group(g).column(i).encodings})
        if name in required and required[name] not in encodings:
            sys.exit(f"{label}: {name} has {encodings}, expected {required[name]}")
        by_duckdb = decode_duckdb(path, name)
        if oracle == "pyarrow":
            column = pf.read([name]).column(0)
            agree = ["duckdb"] if by_duckdb is not None else []
            if by_duckdb is not None and digest(by_duckdb) != digest(column):
                sys.exit(f"{label}: PyArrow and DuckDB decode {name} differently")
        else:
            column = by_duckdb
            try:
                agree = ["pyarrow"] if digest(pf.read([name]).column(0)) == digest(column) else sys.exit(f"{label}: {name} differs")
            except OSError:
                agree = []
        nulls, h = digest(column)
        columns[name] = {"physical": md.schema.column(i).physical_type, "encodings": encodings, "nulls": nulls, "fnv64": h, "also": agree}
    first = md.row_group(0).column(0)
    return {
        "label": label,
        "file": f"encodings/{path.name}",
        "size": path.stat().st_size,
        "rows": md.num_rows,
        "row_groups": md.num_row_groups,
        "codec": first.compression,
        "created_by": md.created_by,
        "oracle": oracle,
        "columns": columns,
    }


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    t = table(ROWS)
    entries = []
    for codec in ("none", "lz4"):
        for version in ("1.0", "2.0"):
            label = f"pyarrow-{codec}-v{version[0]}"
            path = OUT / f"{label}.parquet"
            write_pyarrow(t, path, codec, version)
            entries.append(describe(path, label, PYARROW_ENCODINGS, "pyarrow"))
    one = OUT / "pyarrow-one-row.parquet"
    write_pyarrow(table(1), one, "none", "2.0")
    entries.append(describe(one, "pyarrow-one-row", PYARROW_ENCODINGS, "pyarrow"))
    for codec in ("uncompressed", "snappy"):
        label = f"duckdb-{codec}"
        path = OUT / f"{label}.parquet"
        write_duckdb(t, path, codec)
        entries.append(describe(path, label, DUCKDB_ENCODINGS, "duckdb"))
    (OUT / "expected.json").write_text(json.dumps({
        "generator": "tools/make_encoding_fixtures.py",
        "pyarrow": pa.__version__,
        "duckdb": duckdb.__version__,
        "files": entries,
    }, indent=1) + "\n")
    total = sum(p.stat().st_size for p in OUT.glob("*.parquet"))
    print(f"{len(entries)} fixtures, {total} bytes of Parquet, expected.json written to {OUT}")


if __name__ == "__main__":
    main()
