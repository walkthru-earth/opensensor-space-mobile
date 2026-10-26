package space.opensensor.settings

import space.opensensor.storage.RetentionPolicy
import space.opensensor.units.UnitSystem

/**
 * How much the UI shows. Cosmetic only. Every level reads the same data, higher levels reveal more,
 * and nothing the Simple experience needs depends on them (AGENTS.md). Same three levels, same
 * names, on Android and iOS.
 */
enum class DetailLevel {
    /** The current reading and today, in plain words. History and files stay one tap away. */
    SIMPLE,

    /** Every measurement, storage counters, the Files chart and Metrics inspector. */
    DETAILED,

    /** Detailed plus the Debug screen (raw status JSON, frames, serial log, raw commands). */
    DEVELOPER,
    ;

    val detailed: Boolean get() = this != SIMPLE
    val developer: Boolean get() = this == DEVELOPER
}

/** Which public feed the Discover map reads. Unknown stored names read as [SAMPLE]. */
enum class DiscoverSource {
    /** The bundled sample feed. */
    SAMPLE,

    /** Live licensed stations (OpenAQ). */
    OPENAQ,
}

/** The feed Discover actually uses. Public sensors off always means the bundled [DiscoverSource.SAMPLE]. */
fun effectiveDiscoverSource(source: DiscoverSource, showPublicSensors: Boolean): DiscoverSource =
    if (showPublicSensors) source else DiscoverSource.SAMPLE

/**
 * Every app-level preference with its default in one place. Nothing here is a device setting, those
 * live on the device and are read and written with `GET_CONFIG` and `SET_CONFIG`.
 */
data class AppPrefs(
    val detailLevel: DetailLevel = DetailLevel.SIMPLE,
    /**
     * "Show public sensors". Off, Discover makes no network request at all (no basemap, no public
     * feed) and shows the bundled sample on a plain background (AGENTS.md network boundary).
     */
    val showPublicSensors: Boolean = true,
    /** Display units, Automatic (the phone's region) by default. Display only, stored data stays SI. */
    val unitSystem: UnitSystem = UnitSystem.AUTO,
    /** Byte caps of the rebuildable caches (#61). No screen edits them yet, the stored value wins at start. */
    val retention: RetentionPolicy = RetentionPolicy(),
    /** Which public feed Discover reads. Applies only while [showPublicSensors] is on. */
    val discoverSource: DiscoverSource = DiscoverSource.SAMPLE,
)
