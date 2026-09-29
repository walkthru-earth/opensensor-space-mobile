#!/usr/bin/env python3
"""Exercise the actual v8→v9 SQL against Room exports using stdlib SQLite.

Run from any directory: python3 tools/validate_station_epoch_migration.py
This checks migration SQL and exported schema, not Android Room plumbing or radios.
Only synthetic fixtures are used; credential contents are never printed.
"""

import json
import re
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/earth.walkthru.aqsync.data.local.database.AppDatabase"
DATABASE_SOURCE = ROOT / (
    "app/src/main/java/earth/walkthru/aqsync/data/local/database/AppDatabase.kt"
)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def exported_database(version):
    schema = json.loads((SCHEMAS / f"{version}.json").read_text())["database"]
    require(schema["version"] == version, "Unexpected Room export version")
    return schema


def create_database(schema):
    connection = sqlite3.connect(":memory:")
    connection.execute("PRAGMA foreign_keys = ON")
    for entity in schema["entities"]:
        table = entity["tableName"]
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", table))
    return connection


def migration_sql():
    source = DATABASE_SOURCE.read_text()
    require(source.count("val MIGRATION_8_9:") == 1, "Migration declaration missing or ambiguous")
    body = source.split("val MIGRATION_8_9:", 1)[1].split("val MIGRATIONS:", 1)[0]
    body = re.sub(r"//[^\n]*", "", body)
    constants = re.findall(r'db\.execSQL\(\s*("(?:[^"\\]|\\.)*")\s*\)', body)
    require(len(constants) == body.count("db.execSQL("), "Migration must use literal SQL statements")
    require(len(constants) == 3, "Review validator fixtures when migration statements change")
    statements = [json.loads(value) for value in constants]
    require(all("$" not in sql for sql in statements), "Interpolated migration SQL is unsupported")
    return statements


def table_shape(connection, table):
    # Names come from checked-in Room exports, not user input.
    quoted = '"' + table.replace('"', '""') + '"'
    columns = list(connection.execute(f"PRAGMA table_info({quoted})"))
    foreign_keys = list(connection.execute(f"PRAGMA foreign_key_list({quoted})"))
    indexes = []
    for _, name, unique, origin, partial in connection.execute(f"PRAGMA index_list({quoted})"):
        quoted_index = '"' + name.replace('"', '""') + '"'
        index_columns = list(connection.execute(f"PRAGMA index_xinfo({quoted_index})"))
        indexes.append((name, unique, origin, partial, index_columns))
    return columns, foreign_keys, sorted(indexes)


def insert_fixtures(connection):
    # The token is synthetic opaque ciphertext; do not include row contents in failures.
    connection.executemany(
        "INSERT INTO known_device(address,name,station,deviceId,firmware,lastSeenEpochMs,"
        "lastConnectedEpochMs,lanToken,lanPort,autoSync,companionAssociationId) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        [
            ("device-a", "First", "station-a", "441bf6e26b40", "v1", 10, 20,
             "opaque-test-ciphertext", 47390, 1, 11),
            ("device-b", "Legacy", "station-b", None, "v1", 30, None, None, None, 0, None),
            ("scanned", "Unenrolled", None, None, None, 40, None, None, None, 0, None),
        ],
    )
    connection.executemany(
        "INSERT INTO archived_file(id,station,name,bytes,sha256,crc32,syncedAtEpochMs,"
        "sourceDevice) VALUES(?,?,?,?,?,?,?,?)",
        [
            (1, "station-a", "station=station-a/current.parquet", 8, "hash", 3, 50, "device-a"),
            (2, "station-before", "station=station-before/first.parquet", 8, "hash", 3, 5, "device-a"),
            (3, "station-before", "station=station-before/second.parquet", 8, "hash", 3, 9, "device-a"),
            (4, "station-forgotten", "station=station-forgotten/data.parquet", 8, "hash", 3, 7, "forgotten"),
        ],
    )


def validate():
    old_schema = exported_database(8)
    new_schema = exported_database(9)
    connection = create_database(old_schema)
    expected = create_database(new_schema)
    try:
        insert_fixtures(connection)
        device_rows = list(connection.execute("SELECT * FROM known_device ORDER BY address"))
        archive_rows = list(connection.execute("SELECT * FROM archived_file ORDER BY id"))
        for sql in migration_sql():
            connection.execute(sql)

        require(device_rows == list(connection.execute("SELECT * FROM known_device ORDER BY address")),
                "Migration changed known-device rows or credentials")
        require(archive_rows == list(connection.execute("SELECT * FROM archived_file ORDER BY id")),
                "Migration changed archive rows or original paths")
        tables = {row[0] for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )}
        require(tables == {entity["tableName"] for entity in new_schema["entities"]},
                "Migrated tables differ from the v9 Room export")
        for table in sorted(tables):
            require(table_shape(connection, table) == table_shape(expected, table),
                    f"Migrated table differs from the v9 Room export: {table}")

        require(list(connection.execute("SELECT * FROM station_epoch ORDER BY address, station")) == [
            ("device-a", "station-a", "441bf6e26b40", 20, 0),
            ("device-a", "station-before", None, 5, 0),
            ("device-b", "station-b", None, 30, 0),
            ("forgotten", "station-forgotten", None, 7, 0),
        ], "Epoch backfill lost provenance, invented verification, or used the wrong timestamp")

        connection.execute("INSERT OR IGNORE INTO station_epoch VALUES(?,?,?,?,?)",
                           ("device-a", "station-a", "441bf6e26b40", 99, 1))
        require(connection.execute(
            "SELECT firstSeenEpochMs, observedFromAuthenticatedBle FROM station_epoch "
            "WHERE address=? AND station=?", ("device-a", "station-a")
        ).fetchone() == (20, 0), "Repeated observation changed first-seen provenance")
        connection.execute("INSERT OR IGNORE INTO station_epoch VALUES(?,?,?,?,?)",
                           ("device-a", "station-new", "441bf6e26b40", 100, 1))
        connection.execute("DELETE FROM known_device WHERE address=?", ("device-a",))
        require(connection.execute(
            "SELECT COUNT(*) FROM station_epoch WHERE address=?", ("device-a",)
        ).fetchone() == (3,), "Forgetting a device discarded station lineage")
        require(archive_rows == list(connection.execute("SELECT * FROM archived_file ORDER BY id")),
                "Forgetting a device discarded archive provenance")
    finally:
        connection.close()
        expected.close()
    print("PASS: v8→v9 SQL matches Room export; credentials/archive rows retained; "
          "legacy and archive-only epochs preserved; no invented identity or verification; "
          "first observations immutable; forgetting retains lineage.")


if __name__ == "__main__":
    validate()
