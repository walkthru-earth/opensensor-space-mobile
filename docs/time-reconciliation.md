# Automatic UTC and local Parquet reconciliation

Decision: 2026-10-09. The mobile archive owns corrected UTC partitions. The ESP32 owns
sample timing and durable anchor evidence. The server is not required for local recovery.

## Connection and storage

Android and iOS automatically send their system UTC after the authenticated connection
is ready, before listing files. There is no user-facing device-time setting or confirmation.
The optional microsecond remainder in `SET_TIME` retains the phone's millisecond precision;
legacy firmware falls back to integer seconds. Only the acknowledged UTC/monotonic pair is
persisted as evidence, bound to the connection's original station and full boot ID.

A phone does not need internet to supply its system clock or reconcile files. On supported
Wi-Fi firmware, SNTP supplies a network anchor independently. A Wi-Fi-only recovery dates
new samples immediately and preserves evidence on the card; older derived partitions rebuild
when a phone next receives those files. No on-card history rewrite or server availability is
required. Loss of a radio link does not
invalidate the mapping while the same boot continues. A reboot starts a new monotonic clock
and boot identity. A valid RTC can seed it; otherwise UTC remains null until a new anchor.

The device retains monotonic microseconds in its unknown-UTC Parquet files, under
`station=<id>/unsynced/boot=<id>/`. No Unix-epoch date or phone-local date is invented for them.
Unanchored batches finalize after six genuine samples (normally about one minute), and an
accepted anchor flushes any shorter pending batch. Footer `reconciliation_anchor_mono_us`,
`reconciliation_anchor_utc_ns` and `reconciliation_anchor_source` preserve same-boot evidence
in newly finalized files. The first genuine timed sample also finalizes promptly.

## Reconciliation

1. Persist the clock acknowledgement in an immutable app-private receipt.
2. Import boot-scoped footer anchors from verified downloaded files.
3. Estimate the UTC span of earlier unknown-UTC files of that same station and boot.
4. Rebuild affected UTC day partitions and history buckets, splitting rows at UTC midnight.
5. Retain original paths and bytes; corrected time goes in `event_time_utc_estimated_ns`,
   while the original `event_time_utc_ns` remains null.

The mapping is `anchor_utc_ns + (row_monotonic_us - anchor_monotonic_us) * 1000`.
Source signatures include the anchor so new evidence invalidates the derived cache.
Boot plus sequence remains row identity; moving a row to another UTC partition does not
create another sample. Partitioning uses UTC, regardless of the phone's display timezone.
App restart reloads receipts and rebuilds missing or stale derived output. Both platforms use
`DailyParquetBuilder` to write `derived/v1/<station>/day=YYYY-MM-DD.parquet` and a source manifest.
The layout stays compatible; new files identify the shared writer as `derived_version=2`.

ESP32 history rewriting would compete with acquisition, require a decoder, and risk the
only raw copy. Server-only recovery would delay offline use and depend on a service that
is not deployed. The mobile archive already has a decoder, storage and derived history;
it can reconcile as soon as evidence and verified raw files are present. The server's
[revision protocol remains future work](../../air-quality-sync/docs/time-reconciliation.md);
corrected records must not be re-uploaded as new independent samples.

## Limits

A boot that ended before retaining any valid anchor cannot be dated precisely from a later
boot: the powered-off duration is unknowable without independent clock evidence. Keep those
files undated. Previously finalized raw files are never rewritten to fabricate that evidence.

Microsecond resolution is not microsecond accuracy. Phone clock error, Bluetooth latency,
SNTP error and oscillator drift remain; reconstructed times are labelled estimated. The
history drift assumption is 50 ppm, not a measured total uncertainty bound.

An interrupted RAM batch can still be lost, and an interrupted `.partial` remains retained
for diagnosis. Smaller unknown-UTC batches reduce the exposure but are not a durable journal.
Host tests and builds do not establish radio behavior, RTC retention or power-cut durability;
those require separately recorded Android, iPhone and board runs.

## Format and timer references

[Parquet UTC logical timestamps](https://parquet.apache.org/docs/file-format/types/logicaltypes/)
define epoch-based millisecond, microsecond and nanosecond representations. The firmware's
monotonic clock is boot-relative; see [Espressif ESP Timer](https://docs.espressif.com/projects/esp-idf/en/release-v5.2/esp32s3/api-reference/system/esp_timer.html).
The platform's [system-time documentation](https://docs.espressif.com/projects/esp-idf/en/v5.2/esp32s3/api-reference/system/system_time.html)
describes SNTP synchronization separately from RTC retention. Actual build versions and
board evidence remain in the firmware repository.
