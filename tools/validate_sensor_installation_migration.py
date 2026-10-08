#!/usr/bin/env python3
"""Check v9→v10 SQL against Room's exported schemas with synthetic records."""

from validate_station_epoch_migration import (
    create_database, exported_database, migration_sql, require, table_shape,
)


def validate():
    statements = migration_sql(9, 10)

    old = create_database(exported_database(9))
    expected = create_database(exported_database(10))
    try:
        old.execute(
            "INSERT INTO known_device(address,name,lastSeenEpochMs,autoSync) VALUES(?,?,?,?)",
            ("logger-1", "AQ", 100, 1),
        )
        old.execute(
            "INSERT INTO archived_file(station,name,bytes,crc32,sha256,syncedAtEpochMs,sourceDevice) "
            "VALUES(?,?,?,?,?,?,?)",
            ("station-1", "existing.parquet", 8, 123, "hash", 200, "logger-1"),
        )
        before = {
            table: list(old.execute(f"SELECT * FROM {table}"))
            for table in ("known_device", "archived_file", "station_epoch")
        }
        for statement in statements:
            old.execute(statement)
        require(all(before[table] == list(old.execute(f"SELECT * FROM {table}")) for table in before),
                "Migration changed prior device or archive data")
        tables = {row[0] for row in old.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )}
        require(tables == {entity["tableName"] for entity in exported_database(10)["entities"]},
                "Migrated tables differ from v10 export")
        for table in tables:
            require(table_shape(old, table) == table_shape(expected, table),
                    f"Migrated shape differs from v10 export: {table}")
        old.execute(
            "INSERT INTO sensor_installation(address,station,vendor,model,serial,batch,startedAtEpochMs,source) "
            "VALUES(?,?,?,?,?,?,?,?)",
            ("logger-1", "station-1", "Plantower", "PMS5003T", "PMS5003T-202604081332",
             "PMS5003T-20260408", 300, "barcode"),
        )
        require(old.execute("SELECT COUNT(*) FROM sensor_installation").fetchone() == (1,),
                "Cannot record a sensor installation")
    finally:
        old.close()
        expected.close()
    print("PASS: v9→v10 SQL matches Room export; prior device/archive data retained; installation insert works.")


if __name__ == "__main__":
    validate()
