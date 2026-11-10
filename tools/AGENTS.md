# Tools

Host scripts and one Gradle module. None of them ships in an app. See
`../docs/build-and-release.md` for the hooks, CI and release steps that call them.

| Path | What it does |
| --- | --- |
| `setup-hooks.sh` | Installs the pre-commit and pre-push hooks of `.pre-commit-config.yaml`, once per clone |
| `hooks/ktlint.sh` | The pre-commit ktlint run on the staged Kotlin files, with a pinned binary |
| `setup-signing.sh` | Creates the release keystore under `secrets/` and sets the GitHub Actions signing secrets. It never prints a password |
| `check_apk_abis.py` | Fails when an APK ships native code for an ABI the app does not support. `verify.yml` and `release.yml` run it |
| `check_live_key_drift.py` | Fails when the firmware has a LIVE dictionary digest the app fixtures do not know. `live-key-drift.yml` runs it |
| `check_discover_collection.py` | Fails when the live Discover Collection lists no cells file the app can draw. `live-key-drift.yml` runs it |
| `make_parquet_fixtures.py` | Builds the Parquet reader fixtures of `:core:parquet` with PyArrow as the oracle |
| `make_encoding_fixtures.py` | Builds the `DELTA_BINARY_PACKED` and `BYTE_STREAM_SPLIT` fixtures |
| `make_geoparquet_fixtures.py` | Builds the GeoParquet fixtures with PyArrow and DuckDB as oracles |
| `validate_parquet_samples.py` | Reads the files the Kotlin `ParquetWriter` tests write with PyArrow and DuckDB |
| `discover-fixture/make_discover_sample.py` | Writes the bundled Discover sample feed |
| `discover-fixture/make_openaq_fixture.py` | Writes the OpenAQ aggregates fixture of the `:core:publicfeed` tests |
| `discover-fixture/make_basemap_style.mjs` | Writes the light and dark basemap styles of `:ui:map` |
| `places/make_places.py` | Writes the bundled offline place list of the map search from GeoNames cities15000 |
| `history-benchmark/` | `:tools:history-benchmark`, a kotlinx-benchmark of the History derive on a synthetic day. Its README has the commands, and its results do not qualify phone latency |

## Rules

- The Python fixture builders run from the firmware checkout so its pixi environment supplies
  PyArrow and DuckDB. The header of each script gives the exact command.
- A generated fixture is committed with the script that wrote it. Regenerate it with the script,
  never by hand.
- Only `setup-signing.sh` touches `secrets/`. No other script reads it or `local.properties`.
- The Swift host checks are `../ios/tests/verify-native.sh`, see `../ios/AGENTS.md`.
