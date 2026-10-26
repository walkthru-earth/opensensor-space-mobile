# Shared core, the ThingKind contract

Part of [Thing plugins](README.md). Proposal, 2026-10-27.

## 1. What exists today

Ready for plugins.
- Open vocabulary. `Role`, `Aggregate` and `Family` are open value classes, so an unknown id still parses (`core/model/.../Vocabulary.kt:10,30,55`).
- Templates resolve served before bundled (`BoardTemplate.kt:25-39`). A board with no bundled profile gets a derived one (`AppProfile.kt:63-69`). `Thing.join` combines template, profile and Collection (`Thing.kt:118-134`). `BundledCatalog` reads generated `BundledFiles` with digest checks.
- Ports. Transport sits behind `LinkDriver` and `FrameSink`, storage behind `ArchiveStore` and `ArchiveIndex`, Discover behind `PublicFeed` and `FeedReader`, H3 behind `expect`/`actual`.
- `FileTemplates` and `OpenSensorFooter` resolve any file by its footer. `FileItems.build` is pure. `ParquetWriter` is common code, so a phone can write archive files.

Tied to boards or air quality.
- The family registry and capabilities named in `docs/devices-and-discovery.md` do not exist as types.
- `Metrics.kt` (`core/history/.../data/history/Metrics.kt`) holds air only scales (pm25, pm10, lines 115-116), fixed EPA correction columns, and named PM constants through `requireNotNull` (line 222). The catalog fails to build if a bundled template drops one of those columns.
- `DeviceCapabilities.showsAirCards` checks `Family.AIR` and the `pm25` scale. `configurable` is `proto >= 2`.
- `LiveReading.PMS_STATUS` sits in `:core:protocol`. `TodayCards.kt` lists CF1 columns by name. `TodaySoFarFlow.kt` asks for PM2.5 directly. `LiveColumns` picks temperature and humidity by unit.
- `KnownDevice` is keyed by Bluetooth address. `TransportKind` is a closed enum (`BLE`, `LAN`, `FAKE`). `CollectionLink` wraps a wire `DeviceSession`, so `CollectionController` and `SyncEngine` only speak the firmware protocol.
- `FileItems` assumes the `data_HHMM_` file name and one source.coop root.
- Test fixtures select templates with `startsWith("cores3-")`.

## 2. Contract

New module `:core:thing`, commonMain only.

```kotlin
@JvmInline value class KindId(val id: String)      // "opensensor-board", "phone", "health", "public-feed"
@JvmInline value class Capability(val id: String)  // "ble-sync", "live", "config", "record"

interface ThingKind {
    val id: KindId
    val contractVersion: Int
    fun recognize(evidence: Evidence): Recognition?   // advert, INFO dict, mDNS, feed id
    fun capabilities(thing: ThingId): Set<Capability>   // ThingId, section 6
    val templates: TemplateSource                     // bundled, served or app generated
    val source: DataSource
    val config: ConfigSchema                          // see setup-wizard.md
    val setup: List<SetupStep>
    val permissions: Set<PermissionNeed>              // merged into the :ui:permissions trigger table
}

// Only recording sources carry a publish policy, so a PrivateRead kind cannot be publishable.
sealed interface DataSource {
    sealed interface Recording : DataSource { val publish: PublishPolicy }
    class PullSync(val links: LinkProvider, override val publish: PublishPolicy) : Recording       // today's CollectionController
    class LocalSampling(val samplers: Map<String, SamplerPort>, override val publish: PublishPolicy) : Recording // phone session, ParquetWriter to ArchiveStore
    class RemoteFeed(val feed: PublicFeed) : DataSource           // feed only Things, self uploading devices, already public
    class PrivateRead(val reader: PrivateSourcePort) : DataSource // health, never an archive file, never published
}

sealed interface PublishPolicy {
    data object Never : PublishPolicy
    data class OptInCcBy(val maxRes: Int, val trimSessionEnds: Boolean) : PublishPolicy
}

class ThingKinds(private val kinds: List<ThingKind>) {
    init { require(kinds.map { it.id }.toSet().size == kinds.size) }
    fun recognize(e: Evidence) = kinds.firstNotNullOfOrNull { it.recognize(e) }
}
```

The phone sampling port, shared by both platforms.

```kotlin
interface SamplerPort {
    fun capabilities(): Set<String>                    // metric ids from the template, never invented
    fun authorization(): AccessStatus
    suspend fun requestAccess(): AccessStatus          // called only from a user tap
    fun start(session: RecordingSession, sink: SampleSink)
    fun stop()
}
```

The `SampleSink` is thread safe and never blocks. It hands rows to one session writer in common code, which writes `data_<HHMM>_<boot>_<seq>` files through `ParquetWriter` with the session id in the boot segment, verifies them like a downloaded file, and adds them to the archive. Session start, stop, trimming and the per install id live once in common code.

## 3. Registration

- Kotlin/Native has no `ServiceLoader` (it is in `java.base`) and almost no reflection. Top level properties initialise lazily and `@EagerInitialization` is deprecated ([Kotlin API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native/-eager-initialization/)). So plugins never register themselves from static code.
- Android's Hilt graph and iOS `AppServices.launch` each build `ThingKinds(listOf(...))` once. A simulator test asserts the expected count.
- Per kind config JSON uses a sealed hierarchy, or each kind contributes a `SerializersModule`. Open polymorphism needs explicit registration on Native ([kotlinx.serialization](https://kotlinlang.org/docs/serialization-polymorphism.html)).
- A KSP processor that generates the list, as Home Assistant Companion does with `@ProvidesSensor`, is an option once there are more than a handful of kinds. Not needed now.

## 4. expect/actual versus injected ports

- `expect`/`actual` only for pure stateless platform code. H3 (done), SHA-256, zlib.
- Injected ports for anything with permissions, lifecycle or Swift. Barometer, microphone level, location to cell, HealthKit, Health Connect, LoRa gateways, cloud credentials. Built in `app/` and `ios/` like `LinkDriver` is today.
- Location goes through one shared function, `CellFix.of(lat, lng, res): Long`. The fix never leaves it.

## 5. Data versus code

Data only.
- A new board on BLE or LAN is the firmware template JSON in the bundle plus an optional `AppProfile`.
- The phone template is generated by the app build from a #117 style board manifest (`phone-ios`, `phone-android`). Waits on stac-opensensor#2 section A.
- Recognition rules (advert service UUID, mDNS type, feed id) can be a small bundled JSON per kind.
- Config fields, limits and confirmation gates, once firmware owns a `config` block (see [setup-wizard.md](setup-wizard.md)).

Code.
- A new `DataSource`, `LinkDriver` or sampler.
- A derivation such as the EPA PM2.5 correction, moved into a `DerivedMetricProvider` keyed by `Family`. The air one lives in `:core:airquality`, so `Metrics.of` becomes generic.
- A hero composable, registered by `profile.hero` (see [screens-and-map.md](screens-and-map.md)).

## 6. Identity and versioning

- New Room table `thing_source(thing_uuid PK, kind, kind_version, config_json)` in `:core:database`. Identity moves off the Bluetooth address. `KnownDevice` stays as the BLE adapter row.
- A `ThingId` sealed type (`BleAddress`, `PhoneInstall(uuid)`) replaces the address string in `CollectionIntent`, notifications and iOS bridges. This is one migration, done once.
- Templates stay versioned by `dict` and `desc`. Kinds by `KindId` plus `contractVersion`.

## 7. Health enforced by type

`FileItems` gets one caller, `PublishGate`, which issues a ticket only when the source is `DataSource.Recording` and its `publish` is `OptInCcBy`. Every other source gets no ticket, whatever else it declares. `PrivateRead` data uses a private storage class and never touches `archive/`. A contract test asserts that no `PrivateRead` or `RemoteFeed` kind can obtain a ticket.

## 8. Tests

- `:core:thing:testkit` runs in `commonTest`, so it is gated on JVM and the iOS simulator. Tests split by source.
  - Every kind. Unique `KindId`, recognition from recorded evidence, capability ids come from the template, `PublishGate` gives a ticket only to `Recording` with `OptInCcBy`, and no fix or secret reaches a log.
  - `Recording` kinds only. Written files pass `ParquetVerify` and `OpenSensorFooter.parse`, `FileTemplates` resolves them, `Metrics.of` returns at least one metric, and `FileItems` gives the expected item.
  - `RemoteFeed` kinds. Feed rows map to the template's properties, nothing is written to `archive/`.
  - `PrivateRead` kinds. Nothing is written to `archive/` and no ticket is issued.
- Fixtures are recorded `LinkDriver` scripts (as `FakeTransport` does) and recorded sampler traces. DuckDB and PyArrow stay the oracles.
- Fixtures pick templates by pinned `dict`, never by schema prefix.

## 9. Modules

| Module | Holds |
| --- | --- |
| `:core:thing` | contracts, `ThingKinds`, `PublishGate`, `CellFix`, session writer |
| `:core:thing:testkit` | `ThingKindContract` |
| `:core:kind:board` | wraps today's sync path |
| `:core:kind:phone` | sampler session and template |
| `:core:kind:feed` | public feed and self uploading Things |
| `:core:kind:health` | later, private read only |

All use `opensensor.kmp.library`, so the gates pick them up with no list edit.

## 10. Risks

- Contracts unsettled for phones (stac-opensensor#2 sections A to C).
- Over-abstraction. `CollectionController` stays the board kind's internals, never a universal pipe.
- Swift API growth. Export only `ThingKinds.recognize` and the sampler ports.
