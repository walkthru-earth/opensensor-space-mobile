# Slice 1. Shared Mood Model, MoodStrip, Simple Discover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal.** One shared mood model keyed on `WhoStep`, one mood vocabulary in `:ui:designsystem`, a `MoodStrip` and `MoodLegend` that are each one accessibility node, and a Discover screen that speaks in moods at the Simple level while Detailed and Developer stay unchanged.

**Architecture.** `StationSummary` and `MoodBlock` live in `:core:airquality` commonMain, so Discover never depends on `:core:history`. `PublicSummary` in `:core:publicfeed` folds three feed windows of one cell into a `StationSummary`. `:ui:designsystem` owns the mood words, the one line advice, the peacock mapping by step and the two composables. `DiscoverController` stays level agnostic and, on `selectCell`, reads the other two windows for that one cell. `DiscoverScreen` takes a `DetailLevel` and branches the legend, the In view chart and the cell sheet.

**Tech Stack.** Kotlin Multiplatform (commonMain for JVM, Android and iOS), Compose Multiplatform, Compose resources, kotlinx.coroutines and coroutines-test, `androidx.compose.ui.test.v2.runComposeUiTest` on the iOS simulator, Hilt only in `app/`.

**Spec.** `docs/superpowers/specs/2026-10-12-stations-ux-design.md`, section "Slice 1. Shared mood model, MoodStrip, Simple Discover". Slices 2 to 4 are out of scope.

## Global Constraints

- No app back compatibility. App side types and keys may break. The firmware wire contract (`../aq-parquet/docs/shared/`) is untouched, no frame, opcode, UUID, JSON key or archive path changes.
- Shared code goes in `commonMain`. Common code never imports `java.*`. `:core:*` stays free of Android, Hilt and Room.
- Copy rules (enforced by `CopyLintTest`). No em dash or long dash, no colon, no semicolon in user facing strings. Every new string has English and Arabic (`TranslationsTest`, same keys and placeholders per language). No English word literals in `feature/*/commonMain`, `ios/shared` or `ui/permissions` (`PresentationLiteralsTest`), so "n/a" is a string resource.
- Simple never shows the word "WHO" in any form. Colours and moods still come from `WhoStep`.
- A missing value prints "n/a". Never zero, never a dash, never an invented mood.
- The strip has no axis. `MoodStrip` and `MoodLegend` are each exactly one accessibility node with a spoken summary.
- Every `clickable` passes `role =` (`SemanticsLintTest`).
- Keep PIN, Wi-Fi PSK and LAN token out of logs, frames, screenshots and docs. Public data carries H3 cells, never coordinates. The per cell window reads are the same anonymous feed GETs, gated by Show public sensors through the same `PublicFeed`.
- Gate before the final commit is `./gradlew --no-daemon verify`. Inside tasks run only the narrowest Gradle task named in the step.
- Commit messages are short and imperative with no prefix. The pre-commit hook runs ktlint and formats staged files in place. If it rewrites a file, `git add` that file again and rerun the same `git commit`.

## Review Focus

1. **A missing value becomes zero or an invented mood.** A null or non finite value must give `value = null`, `step = null` and a null block. Tests `StationSummaryTest.missingAndNonFiniteValuesStayNull` (Task 1) and `PublicSummaryTest.missingWindowsGiveNullBlocks` (Task 2).
2. **A property without a WHO scale gets a mood.** `co2` must give `scaled = false`, `step = null`, no blocks, while the number still shows. Tests `StationSummaryTest.anUnscaledPropertyHasNoMood` (Task 1) and `PublicSummaryTest.anUnscaledPropertyKeepsItsNumberOnly` (Task 2).
3. **"WHO" leaks into Simple copy.** Tests `SimpleCopyTest.simpleCopyNeverNamesTheGuidelineBody` (Task 3) over every `mood_*` and `value_*` key in `:ui:designsystem` and every `discover_mood_*` key in `:feature:discover`, English and Arabic.
4. **The strip or the legend splits into several accessibility nodes.** Tests `SemanticsTest.aMoodStripIsOneSpokenNodeWithAnActionPerBlock` and `SemanticsTest.aMoodLegendIsOneSpokenNode` (Task 3), both asserting no child semantics nodes.
5. **A slow window read of a previous cell lands on the newly selected cell.** Test `DiscoverControllerTest.aSlowReadForAnEarlierCellNeverLandsOnTheNewOne` (Task 5).

## File Map

| File | Change |
| --- | --- |
| `core/airquality/src/commonMain/kotlin/earth/walkthru/aqsync/airquality/StationSummary.kt` | Create. `MoodBlock`, `StationSummary` |
| `core/airquality/src/commonTest/kotlin/earth/walkthru/aqsync/airquality/StationSummaryTest.kt` | Create |
| `core/publicfeed/src/commonMain/kotlin/earth/walkthru/aqsync/publicfeed/PublicSummary.kt` | Create |
| `core/publicfeed/src/commonTest/kotlin/earth/walkthru/aqsync/publicfeed/PublicSummaryTest.kt` | Create |
| `ui/designsystem/build.gradle.kts` | Modify. `api(project(":core:airquality"))` |
| `ui/designsystem/src/commonMain/composeResources/values/strings.xml`, `values-ar/strings.xml` | Modify. Mood words, lines, "n/a" |
| `ui/designsystem/src/commonMain/kotlin/earth/walkthru/opensensor/designsystem/MoodStrip.kt` | Create |
| `ui/designsystem/src/commonMain/kotlin/earth/walkthru/opensensor/designsystem/PeacockExpressions.kt` | Modify. Step overload |
| `ui/designsystem/src/commonTest/kotlin/earth/walkthru/opensensor/designsystem/MoodCopyTest.kt` | Create |
| `ui/designsystem/src/iosTest/kotlin/earth/walkthru/opensensor/designsystem/SemanticsTest.kt` | Modify |
| `ui/strings/src/androidHostTest/kotlin/earth/walkthru/opensensor/strings/SimpleCopyTest.kt` | Create |
| `feature/today`, `feature/history`, `feature/devices`, `app/`, `ios/shared` mood copy | Modify. Repoint to `moodWord`, delete duplicates |
| `feature/discover/build.gradle.kts` | Modify. `:core:settings`, commonTest |
| `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverController.kt` | Modify. `selectedWindows`, `selectedSummary` |
| `feature/discover/src/commonTest/kotlin/earth/walkthru/opensensor/discover/DiscoverControllerTest.kt` | Create |
| `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/CellChartMath.kt` | Modify. `stepBlocks`, `stepBand` |
| `feature/discover/src/androidHostTest/kotlin/earth/walkthru/opensensor/discover/CellChartMathTest.kt` | Modify |
| `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverScreen.kt` | Modify. `level`, Simple legend, strip, cell sheet |
| `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverStrings.kt` | Modify. `moodInfo` |
| `feature/discover/src/commonMain/composeResources/values/strings.xml`, `values-ar/strings.xml` | Modify. `discover_mood_*` |
| `app/src/main/java/earth/walkthru/aqsync/ui/discover/DiscoverRoute.kt` | Modify. `level` |
| `ios/shared/src/iosMain/kotlin/earth/walkthru/opensensor/AppRoot.kt` | Modify. `level` |
| `docs/devices-and-discovery.md`, `docs/screens.md` | Modify |

---

### Task 1. StationSummary and MoodBlock in :core:airquality

**Files**
- Create `core/airquality/src/commonMain/kotlin/earth/walkthru/aqsync/airquality/StationSummary.kt`
- Test `core/airquality/src/commonTest/kotlin/earth/walkthru/aqsync/airquality/StationSummaryTest.kt`

**Interfaces**
- Consumes `WhoGuidelines.forProperty(id: String): HealthScale?`, `HealthScale.stepOf(value: Double): WhoStep?`, `enum class WhoStep`.
- Produces
  - `data class MoodBlock(val step: WhoStep?, val weight: Float = 1f)`
  - `data class StationSummary(val propertyId: String, val value: Double?, val unit: String, val step: WhoStep?, val scaled: Boolean, val updatedEpochMs: Long?, val blocks: List<MoodBlock>)`
  - `StationSummary.Companion.of(propertyId: String, unit: String, value: Double?, updatedEpochMs: Long?, history: List<Double?>): StationSummary`

- [ ] **Step 1. Write the failing test.**

```kotlin
package earth.walkthru.aqsync.airquality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationSummaryTest {
    @Test
    fun pmStepsFollowTheWhoEdgesAtOrBelow() {
        val s = StationSummary.of("pm25", "µg/m³", value = 15.0, updatedEpochMs = 1_000L, history = listOf(15.0, 15.1, 80.0))
        assertTrue(s.scaled)
        assertEquals(15.0, s.value)
        assertEquals(WhoStep.GUIDELINE, s.step)
        assertEquals(listOf(WhoStep.GUIDELINE, WhoStep.IT4, WhoStep.ABOVE_IT1), s.blocks.map { it.step })
        assertEquals(listOf(1f, 1f, 1f), s.blocks.map { it.weight })
        assertEquals(1_000L, s.updatedEpochMs)
    }

    @Test
    fun pm10UsesItsOwnEdges() {
        val s = StationSummary.of("pm10", "µg/m³", value = 46.0, updatedEpochMs = null, history = emptyList())
        assertEquals(WhoStep.IT4, s.step)
        assertTrue(s.blocks.isEmpty())
    }

    @Test
    fun missingAndNonFiniteValuesStayNull() {
        val s = StationSummary.of("pm25", "µg/m³", value = Double.NaN, updatedEpochMs = null, history = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 10.0))
        assertNull(s.value)
        assertNull(s.step)
        assertTrue(s.scaled)
        assertEquals(listOf(null, null, null, WhoStep.GUIDELINE), s.blocks.map { it.step })
    }

    @Test
    fun anUnscaledPropertyHasNoMood() {
        val s = StationSummary.of("co2", "ppm", value = 2_000.0, updatedEpochMs = 5L, history = listOf(400.0, 2_000.0, 5_000.0))
        assertFalse(s.scaled)
        assertEquals(2_000.0, s.value)
        assertNull(s.step)
        assertTrue(s.blocks.isEmpty())
    }
}
```

- [ ] **Step 2. Run it and watch it fail.**

Run `./gradlew --no-daemon :core:airquality:jvmTest --tests 'earth.walkthru.aqsync.airquality.StationSummaryTest'`.
Expected. Compilation fails with `Unresolved reference 'StationSummary'`.

- [ ] **Step 3. Implement.**

```kotlin
package earth.walkthru.aqsync.airquality

/**
 * One block of a mood strip. [step] null means no reading for that block, drawn hatched and
 * spoken "n/a". It is never guessed from its neighbours. [weight] is the block's share of the
 * strip's width, 1 for time blocks, an area count for the Discover spread.
 */
data class MoodBlock(val step: WhoStep?, val weight: Float = 1f)

/**
 * What one station or one public cell says about one property, ready for a mood surface.
 * [scaled] is false for a property without a WHO scale. Its number still shows and no mood is
 * invented, so [step] is null and [blocks] is empty. [blocks] run oldest first.
 */
data class StationSummary(
    val propertyId: String,
    val value: Double?,
    val unit: String,
    val step: WhoStep?,
    val scaled: Boolean,
    val updatedEpochMs: Long?,
    val blocks: List<MoodBlock>,
) {
    companion object {
        /** Missing and non finite values stay null, never zero. */
        fun of(propertyId: String, unit: String, value: Double?, updatedEpochMs: Long?, history: List<Double?>): StationSummary {
            val scale = WhoGuidelines.forProperty(propertyId)
            val shown = value?.takeIf { it.isFinite() }
            return StationSummary(
                propertyId = propertyId,
                value = shown,
                unit = unit,
                step = if (scale == null || shown == null) null else scale.stepOf(shown),
                scaled = scale != null,
                updatedEpochMs = updatedEpochMs,
                blocks = if (scale == null) {
                    emptyList()
                } else {
                    history.map { v -> MoodBlock(v?.takeIf { it.isFinite() }?.let(scale::stepOf)) }
                },
            )
        }
    }
}
```

- [ ] **Step 4. Run it and watch it pass.**

Run `./gradlew --no-daemon :core:airquality:jvmTest --tests 'earth.walkthru.aqsync.airquality.StationSummaryTest' :core:airquality:iosSimulatorArm64Test`.
Expected. `BUILD SUCCESSFUL`, 4 tests pass on JVM, the iOS suite passes.

- [ ] **Step 5. Commit.**

```bash
git add core/airquality/src/commonMain/kotlin/earth/walkthru/aqsync/airquality/StationSummary.kt core/airquality/src/commonTest/kotlin/earth/walkthru/aqsync/airquality/StationSummaryTest.kt
git commit -m "Add shared station summary and mood block"
```

---

### Task 2. PublicSummary in :core:publicfeed

**Files**
- Create `core/publicfeed/src/commonMain/kotlin/earth/walkthru/aqsync/publicfeed/PublicSummary.kt`
- Test `core/publicfeed/src/commonTest/kotlin/earth/walkthru/aqsync/publicfeed/PublicSummaryTest.kt`

**Interfaces**
- Consumes `StationSummary.of(...)` (Task 1), `CellRow.mean(property: String): Double?`, `CellRow.updatedEpochMs: Long`, `enum class FeedWindow { LAST_HOUR, DAY, WEEK }`.
- Produces
  - `object PublicSummary { val ORDER: List<FeedWindow>; fun of(propertyId: String, unit: String, windows: Map<FeedWindow, CellRow?>): StationSummary }`

- [ ] **Step 1. Write the failing test.**

```kotlin
package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.airquality.WhoStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicSummaryTest {
    private val cell = 0x85283473fffffffL

    private fun row(pm25: Double?, updated: Long, co2: Double? = null) = CellRow(
        h3 = cell,
        devices = 3,
        updatedEpochMs = updated,
        stats = buildMap {
            put("pm25", CellStat(n = 3, mean = pm25, min = null, max = null))
            if (co2 != null) put("co2", CellStat(n = 3, mean = co2, min = null, max = null))
        },
    )

    @Test
    fun threeWindowsOldestFirstWithTheDayMeanAsHeadline() {
        val s = PublicSummary.of(
            "pm25",
            "µg/m³",
            mapOf(FeedWindow.WEEK to row(10.0, 100L), FeedWindow.DAY to row(30.0, 300L), FeedWindow.LAST_HOUR to row(80.0, 200L)),
        )
        assertEquals(listOf(FeedWindow.WEEK, FeedWindow.DAY, FeedWindow.LAST_HOUR), PublicSummary.ORDER)
        assertEquals(listOf(WhoStep.GUIDELINE, WhoStep.IT3, WhoStep.ABOVE_IT1), s.blocks.map { it.step })
        assertEquals(30.0, s.value)
        assertEquals(WhoStep.IT3, s.step)
        assertEquals(300L, s.updatedEpochMs)
    }

    @Test
    fun missingWindowsGiveNullBlocks() {
        val s = PublicSummary.of("pm25", "µg/m³", mapOf(FeedWindow.LAST_HOUR to row(12.0, 50L), FeedWindow.DAY to null))
        assertEquals(listOf(null, null, WhoStep.GUIDELINE), s.blocks.map { it.step })
        assertNull(s.value)
        assertNull(s.step)
        assertEquals(50L, s.updatedEpochMs)
    }

    @Test
    fun aWindowWithoutTheStatIsNotAvailable() {
        val s = PublicSummary.of("pm25", "µg/m³", mapOf(FeedWindow.DAY to row(null, 10L)))
        assertNull(s.value)
        assertEquals(listOf(null, null, null), s.blocks.map { it.step })
    }

    @Test
    fun anUnscaledPropertyKeepsItsNumberOnly() {
        val s = PublicSummary.of("co2", "ppm", mapOf(FeedWindow.DAY to row(5.0, 10L, co2 = 900.0)))
        assertFalse(s.scaled)
        assertEquals(900.0, s.value)
        assertNull(s.step)
        assertTrue(s.blocks.isEmpty())
    }

    @Test
    fun noWindowsMeansNoUpdateTime() {
        assertNull(PublicSummary.of("pm25", "µg/m³", emptyMap()).updatedEpochMs)
    }
}
```

- [ ] **Step 2. Run it and watch it fail.**

Run `./gradlew --no-daemon :core:publicfeed:jvmTest --tests 'earth.walkthru.aqsync.publicfeed.PublicSummaryTest'`.
Expected. Compilation fails with `Unresolved reference 'PublicSummary'`.

- [ ] **Step 3. Implement.**

```kotlin
package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.airquality.StationSummary

/**
 * The public summary of one cell. Three windows, oldest first (7 days, 24 hours, last hour), one
 * block each. The headline is the 24 hour mean, the averaging of the WHO guideline. A window that
 * was not read, or holds no stat, is a null block, never zero.
 */
object PublicSummary {
    val ORDER: List<FeedWindow> = listOf(FeedWindow.WEEK, FeedWindow.DAY, FeedWindow.LAST_HOUR)

    fun of(propertyId: String, unit: String, windows: Map<FeedWindow, CellRow?>): StationSummary = StationSummary.of(
        propertyId = propertyId,
        unit = unit,
        value = windows[FeedWindow.DAY]?.mean(propertyId),
        updatedEpochMs = windows.values.filterNotNull().maxOfOrNull { it.updatedEpochMs },
        history = ORDER.map { windows[it]?.mean(propertyId) },
    )
}
```

- [ ] **Step 4. Run it and watch it pass.**

Run `./gradlew --no-daemon :core:publicfeed:jvmTest --tests 'earth.walkthru.aqsync.publicfeed.PublicSummaryTest' :core:publicfeed:iosSimulatorArm64Test`.
Expected. `BUILD SUCCESSFUL`, 5 tests pass on JVM, the iOS suite passes.

- [ ] **Step 5. Commit.**

```bash
git add core/publicfeed/src/commonMain/kotlin/earth/walkthru/aqsync/publicfeed/PublicSummary.kt core/publicfeed/src/commonTest/kotlin/earth/walkthru/aqsync/publicfeed/PublicSummaryTest.kt
git commit -m "Build the public cell summary from three windows"
```

---

### Task 3. Mood copy, MoodStrip and MoodLegend in :ui:designsystem

**Files**
- Modify `ui/designsystem/build.gradle.kts`
- Modify `ui/designsystem/src/commonMain/composeResources/values/strings.xml` and `values-ar/strings.xml`
- Modify `ui/designsystem/src/commonMain/kotlin/earth/walkthru/opensensor/designsystem/PeacockExpressions.kt`
- Create `ui/designsystem/src/commonMain/kotlin/earth/walkthru/opensensor/designsystem/MoodStrip.kt`
- Test `ui/designsystem/src/commonTest/kotlin/earth/walkthru/opensensor/designsystem/MoodCopyTest.kt` (create)
- Test `ui/designsystem/src/iosTest/kotlin/earth/walkthru/opensensor/designsystem/SemanticsTest.kt` (modify)
- Test `ui/strings/src/androidHostTest/kotlin/earth/walkthru/opensensor/strings/SimpleCopyTest.kt` (create)

**Interfaces**
- Consumes `MoodBlock`, `WhoStep` (Task 1, through `api(project(":core:airquality"))`), `AirMood.step: WhoStep` (`:core:history`), `Catalogues.root`, `Catalogues.parse(file)`.
- Produces
  - `@Composable fun moodWord(step: WhoStep?): String`
  - `@Composable fun moodLine(step: WhoStep?): String`
  - `@Composable fun notAvailableText(): String`
  - `internal fun moodWordRes(step: WhoStep?): StringResource`, `internal fun moodLineRes(step: WhoStep?): StringResource`
  - `fun peacockExpressionFor(step: WhoStep?): PeacockExpression` (the `AirMood?` overload delegates to it)
  - `@Composable fun MoodStrip(blocks: List<MoodBlock>, modifier: Modifier = Modifier, captions: List<String?> = emptyList(), selected: Int? = null, onBlock: ((Int) -> Unit)? = null)`
  - `@Composable internal fun MoodStripLayout(blocks: List<MoodBlock>, words: List<String>, captions: List<String?>, selected: Int?, onBlock: ((Int) -> Unit)?, modifier: Modifier = Modifier)`
  - `internal fun moodSummary(words: List<String>, captions: List<String?>): String`
  - `@Composable fun MoodLegend(modifier: Modifier = Modifier, title: String? = null)`
  - `@Composable internal fun MoodLegendLayout(words: List<String>, title: String?, modifier: Modifier = Modifier)`
  - `internal fun legendSummary(words: List<String>, title: String?): String`

- [ ] **Step 1. Write the failing copy test** at `ui/strings/src/androidHostTest/kotlin/earth/walkthru/opensensor/strings/SimpleCopyTest.kt`.

```kotlin
package earth.walkthru.opensensor.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Simple speaks in moods. Its copy never names the guideline body, in any language, even though
 * the colours and moods still come from the WHO steps (docs/superpowers/specs/2026-10-12-stations-ux-design.md).
 */
class SimpleCopyTest {
    private val banned = listOf(Regex("""\bWHO\b"""), Regex("منظمة الصحة"))

    private val designsystem = listOf("values", "values-ar").map { File(Catalogues.root, "ui/designsystem/src/commonMain/composeResources/$it/strings.xml") }
    private val discover = listOf("values", "values-ar").map { File(Catalogues.root, "feature/discover/src/commonMain/composeResources/$it/strings.xml") }

    private fun simpleKeys(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        designsystem.forEach { file ->
            Catalogues.parse(file).filterKeys { it.startsWith("mood_") || it.startsWith("value_") }.forEach { (k, v) -> out["${file.parentFile.name}/$k"] = v }
        }
        discover.forEach { file ->
            Catalogues.parse(file).filterKeys { it.startsWith("discover_mood_") }.forEach { (k, v) -> out["discover/${file.parentFile.name}/$k"] = v }
        }
        return out
    }

    @Test
    fun theMoodVocabularyIsComplete() {
        val words = listOf("fresh", "fair", "hazy", "poor", "smoky", "thick", "none")
        designsystem.forEach { file ->
            val keys = Catalogues.parse(file).keys
            words.forEach { w ->
                assertTrue("mood_$w" in keys, "${file.parentFile.name} lacks mood_$w")
                assertTrue("mood_${w}_line" in keys, "${file.parentFile.name} lacks mood_${w}_line")
            }
            assertTrue("value_not_available" in keys, "${file.parentFile.name} lacks value_not_available")
        }
    }

    @Test
    fun simpleCopyNeverNamesTheGuidelineBody() {
        val found = simpleKeys().filter { (_, text) -> banned.any { it.containsMatchIn(text) } }
        if (found.isNotEmpty()) fail("Simple copy names the guideline body $found")
    }
}
```

- [ ] **Step 2. Run it and watch it fail.**

Run `./gradlew --no-daemon :ui:strings:testAndroidHostTest --tests 'earth.walkthru.opensensor.strings.SimpleCopyTest'`.
Expected. `theMoodVocabularyIsComplete` fails with `values lacks mood_fresh`.

- [ ] **Step 3. Add the strings.**

In `ui/designsystem/src/commonMain/composeResources/values/strings.xml`, before `</resources>`, add

```xml
    <!-- The six air moods, one per WHO step, cleanest first, and the word for no reading. -->
    <string name="mood_fresh">Fresh air</string>
    <string name="mood_fair">Fair</string>
    <string name="mood_hazy">Getting hazy</string>
    <string name="mood_poor">Poor</string>
    <string name="mood_smoky">Smoky</string>
    <string name="mood_thick">Thick smoke</string>
    <string name="mood_none">No reading</string>
    <!-- One plain sentence per mood. Simple shows it, so it never names the guideline body. -->
    <string name="mood_fresh_line">Clean air. A good time to be outside.</string>
    <string name="mood_fair_line">Fine for most people.</string>
    <string name="mood_hazy_line">Sensitive people may notice it.</string>
    <string name="mood_poor_line">Take long outdoor effort easy.</string>
    <string name="mood_smoky_line">Stay inside if you can. A filter helps.</string>
    <string name="mood_thick_line">Stay inside with windows shut.</string>
    <string name="mood_none_line">No reading here yet.</string>
    <!-- A missing value. Never zero, never a dash. -->
    <string name="value_not_available">n/a</string>
```

In `ui/designsystem/src/commonMain/composeResources/values-ar/strings.xml`, before `</resources>`, add

```xml
    <string name="mood_fresh">هواء نقي</string>
    <string name="mood_fair">مقبول</string>
    <string name="mood_hazy">يميل إلى الضبابية</string>
    <string name="mood_poor">سيئ</string>
    <string name="mood_smoky">دخاني</string>
    <string name="mood_thick">دخان كثيف</string>
    <string name="mood_none">لا توجد قراءة</string>
    <string name="mood_fresh_line">هواء نظيف. وقت جيد للخروج.</string>
    <string name="mood_fair_line">مناسب لمعظم الناس.</string>
    <string name="mood_hazy_line">قد يلاحظه الأشخاص الحساسون.</string>
    <string name="mood_poor_line">خفف المجهود الطويل في الخارج.</string>
    <string name="mood_smoky_line">ابقَ في الداخل إن استطعت. المرشح يساعد.</string>
    <string name="mood_thick_line">ابقَ في الداخل والنوافذ مغلقة.</string>
    <string name="mood_none_line">لا توجد قراءة هنا بعد.</string>
    <string name="value_not_available">غير متاح</string>
```

- [ ] **Step 4. Run the copy tests and watch them pass.**

Run `./gradlew --no-daemon :ui:strings:testAndroidHostTest`.
Expected. `BUILD SUCCESSFUL`. `SimpleCopyTest`, `CopyLintTest` and `TranslationsTest` pass.

- [ ] **Step 5. Write the failing common test** at `ui/designsystem/src/commonTest/kotlin/earth/walkthru/opensensor/designsystem/MoodCopyTest.kt`.

```kotlin
package earth.walkthru.opensensor.designsystem

import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.history.AirMood
import kotlin.test.Test
import kotlin.test.assertEquals

class MoodCopyTest {
    @Test
    fun eachStepHasItsWordAndLine() {
        assertEquals(
            listOf("mood_fresh", "mood_fair", "mood_hazy", "mood_poor", "mood_smoky", "mood_thick"),
            WhoStep.entries.map { moodWordRes(it).key },
        )
        assertEquals("mood_none", moodWordRes(null).key)
        assertEquals(
            listOf("mood_fresh_line", "mood_fair_line", "mood_hazy_line", "mood_poor_line", "mood_smoky_line", "mood_thick_line"),
            WhoStep.entries.map { moodLineRes(it).key },
        )
        assertEquals("mood_none_line", moodLineRes(null).key)
    }

    @Test
    fun thePeacockFollowsTheStep() {
        assertEquals(
            listOf(
                PeacockExpression.HAPPY,
                PeacockExpression.CALM,
                PeacockExpression.CONCERNED,
                PeacockExpression.MASKED,
                PeacockExpression.MASKED,
                PeacockExpression.FEATHER_SHIELD,
            ),
            WhoStep.entries.map { peacockExpressionFor(it) },
        )
        assertEquals(PeacockExpression.CONFUSED, peacockExpressionFor(null as WhoStep?))
        AirMood.entries.forEach { assertEquals(peacockExpressionFor(it.step), peacockExpressionFor(it)) }
    }

    @Test
    fun theStripSummaryReadsEachBlockWithItsCaption() {
        assertEquals(
            "Fresh air 7 days, n/a 24 hours, Poor 1 hour",
            moodSummary(listOf("Fresh air", "n/a", "Poor"), listOf("7 days", "24 hours", "1 hour")),
        )
        assertEquals("Fresh air, Poor", moodSummary(listOf("Fresh air", "Poor"), emptyList()))
    }

    @Test
    fun theLegendSummaryNamesTheTitleThenTheWords() {
        assertEquals("PM2.5. Fresh air, Fair", legendSummary(listOf("Fresh air", "Fair"), "PM2.5"))
        assertEquals("Fresh air, Fair", legendSummary(listOf("Fresh air", "Fair"), null))
    }
}
```

- [ ] **Step 6. Run it and watch it fail.**

Run `./gradlew --no-daemon :ui:designsystem:testAndroidHostTest --tests 'earth.walkthru.opensensor.designsystem.MoodCopyTest'`.
Expected. Compilation fails with `Unresolved reference 'moodWordRes'`.

- [ ] **Step 7. Expose `:core:airquality`.** In `ui/designsystem/build.gradle.kts`, inside `commonMain.dependencies`, after `api(libs.compose.material3)` add

```kotlin
            // MoodStrip and the mood words take WhoStep and MoodBlock in their public API.
            api(project(":core:airquality"))
```

- [ ] **Step 8. Map the peacock by step.** In `PeacockExpressions.kt` replace the whole `peacockExpressionFor(mood: AirMood?)` function with

```kotlin
/** Visual mapping only. The metric registry continues to own the scale, labels and thresholds. */
fun peacockExpressionFor(step: WhoStep?): PeacockExpression = when (step) {
    WhoStep.GUIDELINE -> PeacockExpression.HAPPY
    WhoStep.IT4 -> PeacockExpression.CALM
    WhoStep.IT3 -> PeacockExpression.CONCERNED
    WhoStep.IT2, WhoStep.IT1 -> PeacockExpression.MASKED
    WhoStep.ABOVE_IT1 -> PeacockExpression.FEATHER_SHIELD
    null -> PeacockExpression.CONFUSED
}

/** The same mapping for a sensor's mood, through its step. */
fun peacockExpressionFor(mood: AirMood?): PeacockExpression = peacockExpressionFor(mood?.step)
```

and add `import earth.walkthru.aqsync.airquality.WhoStep` to its imports.

- [ ] **Step 9. Implement `MoodStrip.kt`.**

```kotlin
package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.airquality.MoodBlock
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.opensensor.designsystem.resources.Res
import earth.walkthru.opensensor.designsystem.resources.mood_fair
import earth.walkthru.opensensor.designsystem.resources.mood_fair_line
import earth.walkthru.opensensor.designsystem.resources.mood_fresh
import earth.walkthru.opensensor.designsystem.resources.mood_fresh_line
import earth.walkthru.opensensor.designsystem.resources.mood_hazy
import earth.walkthru.opensensor.designsystem.resources.mood_hazy_line
import earth.walkthru.opensensor.designsystem.resources.mood_none
import earth.walkthru.opensensor.designsystem.resources.mood_none_line
import earth.walkthru.opensensor.designsystem.resources.mood_poor
import earth.walkthru.opensensor.designsystem.resources.mood_poor_line
import earth.walkthru.opensensor.designsystem.resources.mood_smoky
import earth.walkthru.opensensor.designsystem.resources.mood_smoky_line
import earth.walkthru.opensensor.designsystem.resources.mood_thick
import earth.walkthru.opensensor.designsystem.resources.mood_thick_line
import earth.walkthru.opensensor.designsystem.resources.value_not_available
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun moodWordRes(step: WhoStep?): StringResource = when (step) {
    WhoStep.GUIDELINE -> Res.string.mood_fresh
    WhoStep.IT4 -> Res.string.mood_fair
    WhoStep.IT3 -> Res.string.mood_hazy
    WhoStep.IT2 -> Res.string.mood_poor
    WhoStep.IT1 -> Res.string.mood_smoky
    WhoStep.ABOVE_IT1 -> Res.string.mood_thick
    null -> Res.string.mood_none
}

internal fun moodLineRes(step: WhoStep?): StringResource = when (step) {
    WhoStep.GUIDELINE -> Res.string.mood_fresh_line
    WhoStep.IT4 -> Res.string.mood_fair_line
    WhoStep.IT3 -> Res.string.mood_hazy_line
    WhoStep.IT2 -> Res.string.mood_poor_line
    WhoStep.IT1 -> Res.string.mood_smoky_line
    WhoStep.ABOVE_IT1 -> Res.string.mood_thick_line
    null -> Res.string.mood_none_line
}

/** The mood word of [step], one vocabulary for every screen. Null is "No reading". */
@Composable
fun moodWord(step: WhoStep?): String = stringResource(moodWordRes(step))

/** One plain sentence for [step]. It never names the guideline body, so Simple can show it. */
@Composable
fun moodLine(step: WhoStep?): String = stringResource(moodLineRes(step))

/** A missing value, "n/a". Never zero and never a dash. */
@Composable
fun notAvailableText(): String = stringResource(Res.string.value_not_available)

/** "word caption" per block, joined with commas. Captions are optional per block. */
internal fun moodSummary(words: List<String>, captions: List<String?>): String =
    words.indices.joinToString(", ") { i -> listOfNotNull(words[i], captions.getOrNull(i)).joinToString(" ") }

/** The legend read as one sentence, the title then the six words. */
internal fun legendSummary(words: List<String>, title: String?): String =
    listOfNotNull(title, words.joinToString(", ")).joinToString(". ")

/**
 * Mood blocks side by side, widths by [MoodBlock.weight], no axis. A null step is hatched and read
 * "n/a". The row follows the layout direction, so it mirrors in right to left. It is one
 * accessibility node that reads every block with its caption. With [onBlock], each block is also a
 * button and a custom action, and [selected] gets an outline.
 */
@Composable
fun MoodStrip(
    blocks: List<MoodBlock>,
    modifier: Modifier = Modifier,
    captions: List<String?> = emptyList(),
    selected: Int? = null,
    onBlock: ((Int) -> Unit)? = null,
) {
    val na = notAvailableText()
    val words = blocks.map { b -> if (b.step == null) na else moodWord(b.step) }
    MoodStripLayout(blocks, words, captions, selected, onBlock, modifier)
}

@Composable
internal fun MoodStripLayout(
    blocks: List<MoodBlock>,
    words: List<String>,
    captions: List<String?>,
    selected: Int?,
    onBlock: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shown = blocks.indices.filter { blocks[it].weight > 0f }
    val summary = moodSummary(shown.map { words[it] }, shown.map { captions.getOrNull(it) })
    val outline = MaterialTheme.colorScheme.onSurface
    val hatch = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier.fillMaxWidth().height(28.dp).clearAndSetSemantics {
            contentDescription = summary
            if (onBlock != null) {
                customActions = shown.map { i ->
                    CustomAccessibilityAction(listOfNotNull(words[i], captions.getOrNull(i)).joinToString(" ")) {
                        onBlock(i)
                        true
                    }
                }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        shown.forEach { i ->
            val block = blocks[i]
            val shape = RoundedCornerShape(6.dp)
            var m = Modifier.weight(block.weight).fillMaxHeight().clip(shape)
            m = if (block.step != null) {
                m.background(Color(block.step.color))
            } else {
                m.background(track).drawBehind {
                    val gap = 6.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(hatch, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
                        x += gap
                    }
                }
            }
            if (i == selected) m = m.border(2.dp, outline, shape)
            if (onBlock != null) m = m.clickable(role = Role.Button) { onBlock(i) }
            Box(m)
        }
    }
}

/** The six mood words with their colours, in two columns. One accessibility node. */
@Composable
fun MoodLegend(modifier: Modifier = Modifier, title: String? = null) {
    val words = WhoStep.entries.map { moodWord(it) }
    MoodLegendLayout(words, title, modifier)
}

@Composable
internal fun MoodLegendLayout(words: List<String>, title: String?, modifier: Modifier = Modifier) {
    val summary = legendSummary(words, title)
    Column(modifier.clearAndSetSemantics { contentDescription = summary }) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
        }
        WhoStep.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { step ->
                    Row(Modifier.weight(1f).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(step.color)))
                        Spacer(Modifier.width(6.dp))
                        Text(words.getOrElse(step.ordinal) { "" }, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 10. Run the common test and watch it pass.**

Run `./gradlew --no-daemon :ui:designsystem:testAndroidHostTest --tests 'earth.walkthru.opensensor.designsystem.MoodCopyTest'`.
Expected. `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 11. Write the failing semantics tests.** In `ui/designsystem/src/iosTest/kotlin/earth/walkthru/opensensor/designsystem/SemanticsTest.kt` add these imports

```kotlin
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onChildren
import earth.walkthru.aqsync.airquality.MoodBlock
import earth.walkthru.aqsync.airquality.WhoStep
```

and add these two tests inside `class SemanticsTest`

```kotlin
    @Test
    fun aMoodStripIsOneSpokenNodeWithAnActionPerBlock() = runComposeUiTest {
        var tapped = -1
        setContent {
            MaterialTheme {
                MoodStripLayout(
                    blocks = listOf(MoodBlock(WhoStep.GUIDELINE), MoodBlock(null), MoodBlock(WhoStep.IT2), MoodBlock(WhoStep.IT1, weight = 0f)),
                    words = listOf("Fresh air", "n/a", "Poor", "Smoky"),
                    captions = listOf("7 days", "24 hours", "1 hour", "never shown"),
                    selected = null,
                    onBlock = { tapped = it },
                    modifier = Modifier.testTag("strip"),
                )
            }
        }
        val threeActions = SemanticsMatcher("three custom actions") { it.config.getOrNull(SemanticsActions.CustomActions)?.size == 3 }
        onNodeWithTag("strip").assert(hasContentDescription("Fresh air 7 days, n/a 24 hours, Poor 1 hour")).assert(threeActions)
        onNodeWithTag("strip", useUnmergedTree = true).onChildren().assertCountEquals(0)
        onNodeWithTag("strip").fetchSemanticsNode().config[SemanticsActions.CustomActions][2].action()
        kotlin.test.assertEquals(2, tapped)
    }

    @Test
    fun aMoodLegendIsOneSpokenNode() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MoodLegendLayout(
                    words = listOf("Fresh air", "Fair", "Getting hazy", "Poor", "Smoky", "Thick smoke"),
                    title = "PM2.5",
                    modifier = Modifier.testTag("legend"),
                )
            }
        }
        onNodeWithTag("legend").assert(hasContentDescription("PM2.5. Fresh air, Fair, Getting hazy, Poor, Smoky, Thick smoke"))
        onNodeWithTag("legend", useUnmergedTree = true).onChildren().assertCountEquals(0)
    }
```

- [ ] **Step 12. Run the semantics tests and watch them pass.**

Run `./gradlew --no-daemon :ui:designsystem:iosSimulatorArm64Test`.
Expected. `BUILD SUCCESSFUL`. Both new tests pass with the existing ones. If either fails because a child node shows up, the `clearAndSetSemantics` call is not on the outermost layout of that composable. Fix the composable, not the test.

- [ ] **Step 13. Run the lint style copy and semantics scans.**

Run `./gradlew --no-daemon :ui:strings:testAndroidHostTest`.
Expected. `BUILD SUCCESSFUL`. `SemanticsLintTest` accepts `clickable(role = Role.Button)`.

- [ ] **Step 14. Commit.**

```bash
git add ui/designsystem ui/strings/src/androidHostTest/kotlin/earth/walkthru/opensensor/strings/SimpleCopyTest.kt
git commit -m "Add mood words, MoodStrip and MoodLegend to the design system"
```

---

### Task 4. One mood vocabulary across the apps

**Files**
- Modify `feature/today/src/commonMain/kotlin/earth/walkthru/opensensor/today/TodayText.kt`, `TodayCards.kt`, `feature/today/src/commonMain/composeResources/values/strings.xml`, `values-ar/strings.xml`
- Modify `feature/history/src/commonMain/kotlin/earth/walkthru/opensensor/history/HistoryColors.kt`, `feature/history/src/commonMain/composeResources/values/strings.xml`, `values-ar/strings.xml`
- Modify `feature/devices/src/commonMain/kotlin/earth/walkthru/opensensor/devices/DeviceRows.kt` (find it with `grep -rl 'fun moodTitle' feature/devices`), `feature/devices/src/commonMain/composeResources/values/strings.xml`, `values-ar/strings.xml`
- Modify `app/src/main/java/earth/walkthru/aqsync/ui/components/MoodText.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ar/strings.xml`
- Modify `ios/shared/src/commonMain/kotlin/earth/walkthru/opensensor/CompanionScreen.kt`
- Test `ui/strings/src/androidHostTest/kotlin/earth/walkthru/opensensor/strings/SimpleCopyTest.kt` (modify)

**Interfaces**
- Consumes `moodWord(step: WhoStep?)` (Task 3), `AirMood.step`.
- Produces no new API. `internal fun AirMood.titleText(): String` in today and history now returns `moodWord(step)`. `noMoodText()` returns `moodWord(null)`.

- [ ] **Step 1. Write the failing test.** Add to `SimpleCopyTest`

```kotlin
    /** The mood words live once, in the design system. A copy elsewhere drifts, as iOS once did. */
    @Test
    fun moodWordsLiveOnlyInTheDesignSystem() {
        val duplicate = Regex("""mood_\w+_title|mood_none""")
        val found = Catalogues.all()
            .filter { !Catalogues.relative(it).startsWith("ui/designsystem/") }
            .flatMap { file -> Catalogues.parse(file).keys.filter { duplicate.matches(it) }.map { "${Catalogues.relative(file)} $it" } }
        if (found.isNotEmpty()) fail("Mood words duplicated outside the design system $found")
    }
```

- [ ] **Step 2. Run it and watch it fail.**

Run `./gradlew --no-daemon :ui:strings:testAndroidHostTest --tests 'earth.walkthru.opensensor.strings.SimpleCopyTest'`.
Expected. `moodWordsLiveOnlyInTheDesignSystem` fails listing `app/src/main/res/values/strings.xml mood_fresh_title`, the today, history and devices catalogues, and `mood_none` in today and history.

- [ ] **Step 3. Today.** In `TodayText.kt` replace the body of `AirMood.titleText()` so it reads

```kotlin
@Composable
internal fun AirMood.titleText(): String = moodWord(step)
```

remove the six imports `earth.walkthru.opensensor.today.resources.mood_fresh_title`, `mood_fair_title`, `mood_hazy_title`, `mood_poor_title`, `mood_smoky_title`, `mood_thick_title`, and add `import earth.walkthru.opensensor.designsystem.moodWord`. In `TodayCards.kt` change `noMoodText()` to

```kotlin
internal fun noMoodText(): String = moodWord(null)
```

keep its `@Composable` annotation, remove `import earth.walkthru.opensensor.today.resources.mood_none`, and add `import earth.walkthru.opensensor.designsystem.moodWord`. In both today catalogues delete the lines with `name="mood_fresh_title"`, `mood_fair_title`, `mood_hazy_title`, `mood_poor_title`, `mood_smoky_title`, `mood_thick_title` and `mood_none`. Keep every `mood_*_advice` and `mood_*_like` line, Slice 2 owns them.

- [ ] **Step 4. History.** In `HistoryColors.kt` replace `AirMood.titleText()` with

```kotlin
@Composable
internal fun AirMood.titleText(): String = moodWord(step)
```

remove the six `earth.walkthru.opensensor.history.resources.mood_*_title` imports, add `import earth.walkthru.opensensor.designsystem.moodWord`. In both history catalogues delete `mood_fresh_title` through `mood_thick_title` and the unused `mood_none`.

- [ ] **Step 5. Devices.** In `DeviceRows.kt` replace `stringResource(moodTitle(mood)),` with `moodWord(mood.step),`, delete the whole `private fun moodTitle(mood: AirMood): StringResource` function, its six `earth.walkthru.opensensor.devices.resources.mood_*_title` imports and `import org.jetbrains.compose.resources.StringResource` (its only user), and add `import earth.walkthru.opensensor.designsystem.moodWord`. Keep the `AirMood` import if another line in the file still uses it, otherwise remove it. In both devices catalogues delete `mood_fresh_title` through `mood_thick_title`.

- [ ] **Step 6. Android app.** In `MoodText.kt` delete the whole `fun AirMood.titleText()` function and `import earth.walkthru.aqsync.data.history.AirMood`. It has no callers (`grep -rn 'titleText()' app/src` shows none for `AirMood`). In `app/src/main/res/values/strings.xml` and `values-ar/strings.xml` delete the six `mood_*_title` lines.

- [ ] **Step 7. iOS companion.** In `CompanionScreen.kt` replace both occurrences of

```kotlin
stringResource(Res.string.companion_pm25_mood, reading.mood.title)
```

with

```kotlin
stringResource(Res.string.companion_pm25_mood, moodWord(reading.mood.step))
```

and add `import earth.walkthru.opensensor.designsystem.moodWord`. This fixes the English mood name printed under Arabic.

- [ ] **Step 8. Run the tests and builds.**

Run `./gradlew --no-daemon :ui:strings:testAndroidHostTest :feature:today:testAndroidHostTest :feature:history:testAndroidHostTest :feature:devices:testAndroidHostTest :app:compileDebugKotlin :ios:shared:compileKotlinIosSimulatorArm64`.
Expected. `BUILD SUCCESSFUL`. `moodWordsLiveOnlyInTheDesignSystem` passes, `StringsMatchAndroidTest` still passes.

- [ ] **Step 9. Commit.**

```bash
git add feature/today feature/history feature/devices app/src/main ios/shared/src/commonMain/kotlin/earth/walkthru/opensensor/CompanionScreen.kt ui/strings/src/androidHostTest/kotlin/earth/walkthru/opensensor/strings/SimpleCopyTest.kt
git commit -m "Use the design system mood words everywhere"
```

---

### Task 5. The controller reads the selected cell's other windows

**Files**
- Modify `feature/discover/build.gradle.kts`
- Modify `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverController.kt`
- Test `feature/discover/src/commonTest/kotlin/earth/walkthru/opensensor/discover/DiscoverControllerTest.kt` (create)

**Interfaces**
- Consumes `PublicFeed.cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable`, `PublicSummary.of(...)` (Task 2), `StationSummary` (Task 1).
- Produces
  - `DiscoverUiState.selectedWindows: Map<FeedWindow, CellRow?>` (default `emptyMap()`)
  - `fun DiscoverUiState.selectedSummary(property: FeedProperty): StationSummary?`

- [ ] **Step 1. Add the test source set and settings.** In `feature/discover/build.gradle.kts`, inside `commonMain.dependencies` after `api(project(":core:units"))`, add

```kotlin
            // DiscoverScreen takes the host's DetailLevel (Simple speaks in moods).
            api(project(":core:settings"))
```

and after the `androidMain.dependencies { ... }` block add

```kotlin
        commonTest.dependencies {
            // DiscoverControllerTest drives the controller on a test scheduler.
            implementation(libs.kotlinx.coroutines.test)
        }
```

- [ ] **Step 2. Write the failing test.**

```kotlin
package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.CellStat
import earth.walkthru.aqsync.publicfeed.CellsFileInfo
import earth.walkthru.aqsync.publicfeed.CellsTable
import earth.walkthru.aqsync.publicfeed.FeedPartition
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.FeedWindow
import earth.walkthru.aqsync.publicfeed.GeoBounds
import earth.walkthru.aqsync.publicfeed.H3Grid
import earth.walkthru.aqsync.publicfeed.H3Index
import earth.walkthru.aqsync.publicfeed.LatLng
import earth.walkthru.aqsync.publicfeed.PublicFeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverControllerTest {
    private val cellA = 0x85283473fffffffL
    private val cellB = 0x8528347bfffffffL
    private val pm25 = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")

    /** pm25 per window. A is 10, 30 and 80 (7 days, 24 hours, last hour). B is 5 in every window. */
    private fun value(cell: Long, window: FeedWindow): Double = when {
        cell == cellB -> 5.0
        window == FeedWindow.WEEK -> 10.0
        window == FeedWindow.DAY -> 30.0
        else -> 80.0
    }

    private inner class FakeFeed : PublicFeed {
        override val isSample = true
        override val available = true
        val calls = mutableListOf<Pair<FeedWindow, List<LongRange>?>>()
        val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()

        override suspend fun latest() = FeedPartition("2026-10-12", 10)

        override suspend fun cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable {
            calls += window to ranges
            ranges?.singleOrNull()?.takeIf { it.first == it.last }?.let { gates[it.first]?.await() }
            val rows = listOf(cellA, cellB)
                .filter { c -> ranges == null || ranges.any { c in it } }
                .map { c -> CellRow(c, devices = 3, updatedEpochMs = 1_000L, stats = mapOf("pm25" to CellStat(3, value(c, window), null, null))) }
            return CellsTable(CellsFileInfo(sample = true, window = null, h3Res = res, properties = listOf(pm25)), rows, 1, 1)
        }
    }

    /** Every viewport covers A and B. Outlines are a small triangle, enough for the GeoJSON. */
    private object FakeGrid : H3Grid {
        override fun latLngToCell(lat: Double, lng: Double, res: Int) = 0x85283473fffffffL
        override fun cellToLatLng(cell: Long) = LatLng(37.0, -122.0)
        override fun cellToBoundary(cell: Long) = listOf(LatLng(37.0, -122.0), LatLng(37.1, -122.0), LatLng(37.0, -122.1))
        override fun polygonToCells(ring: List<LatLng>, res: Int) = listOf(0x85283473fffffffL, 0x8528347bfffffffL)
    }

    private class MemoryPrefs : DiscoverPrefs {
        var saved = DiscoverSaved(firstRunDone = true)
        override suspend fun load() = saved
        override suspend fun save(saved: DiscoverSaved) {
            this.saved = saved
        }
    }

    private fun TestScope.controller(feed: FakeFeed): DiscoverController {
        val c = DiscoverController(backgroundScope, feed, FakeGrid, MemoryPrefs(), "UTC", now = { 1_000L }, io = StandardTestDispatcher(testScheduler))
        // Zoom 6.25 reads resolution 5, the resolution of both test cells.
        c.onCamera(CameraRest(GeoBounds(-122.01, 36.99, -121.99, 37.01), zoom = 6.25, lat = 37.0, lng = -122.0))
        advanceUntilIdle()
        return c
    }

    @Test
    fun selectingACellReadsItsOtherTwoWindowsForThatCellOnly() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        feed.calls.clear()
        c.selectCell(H3Index.toHex(cellA))
        advanceUntilIdle()
        assertEquals(setOf(FeedWindow.WEEK, FeedWindow.LAST_HOUR), feed.calls.map { it.first }.toSet())
        assertTrue(feed.calls.all { it.second == listOf(cellA..cellA) })
        val windows = c.state.value.selectedWindows
        assertEquals(setOf(FeedWindow.WEEK, FeedWindow.DAY, FeedWindow.LAST_HOUR), windows.keys)
        assertTrue(windows.values.all { it?.h3 == cellA })
        val summary = c.state.value.selectedSummary(pm25)!!
        assertEquals(listOf(WhoStep.GUIDELINE, WhoStep.IT3, WhoStep.ABOVE_IT1), summary.blocks.map { it.step })
        assertEquals(30.0, summary.value)
    }

    @Test
    fun aSlowReadForAnEarlierCellNeverLandsOnTheNewOne() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        val slow = CompletableDeferred<Unit>()
        feed.gates[cellA] = slow
        c.selectCell(H3Index.toHex(cellA))
        advanceUntilIdle()
        c.selectCell(H3Index.toHex(cellB))
        advanceUntilIdle()
        slow.complete(Unit)
        advanceUntilIdle()
        val windows = c.state.value.selectedWindows
        assertEquals(cellB, c.state.value.selected?.h3)
        assertEquals(3, windows.size)
        assertTrue(windows.values.all { it?.h3 == cellB }, "stale rows $windows")
    }

    @Test
    fun clearingTheSelectionClearsTheWindows() = runTest {
        val c = controller(FakeFeed())
        c.selectCell(H3Index.toHex(cellA))
        advanceUntilIdle()
        c.selectCell(null)
        advanceUntilIdle()
        assertTrue(c.state.value.selectedWindows.isEmpty())
        assertNull(c.state.value.selectedSummary(pm25))
    }

    @Test
    fun changingTheWindowRereadsTheOthersForTheSelectedCell() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        c.selectCell(H3Index.toHex(cellA))
        advanceUntilIdle()
        feed.calls.clear()
        c.selectWindow(FeedWindow.LAST_HOUR)
        advanceUntilIdle()
        val cellReads = feed.calls.filter { it.second == listOf(cellA..cellA) }.map { it.first }.toSet()
        assertEquals(setOf(FeedWindow.WEEK, FeedWindow.DAY), cellReads)
        assertEquals(3, c.state.value.selectedWindows.size)
        assertEquals(30.0, c.state.value.selectedSummary(pm25)?.value)
    }
}
```

- [ ] **Step 3. Run it and watch it fail.**

Run `./gradlew --no-daemon :feature:discover:testAndroidHostTest --tests 'earth.walkthru.opensensor.discover.DiscoverControllerTest'`.
Expected. Compilation fails with `Unresolved reference 'selectedWindows'` and `Unresolved reference 'selectedSummary'`.

- [ ] **Step 4. Implement.** In `DiscoverController.kt`

Add imports

```kotlin
import earth.walkthru.aqsync.airquality.StationSummary
import earth.walkthru.aqsync.publicfeed.PublicSummary
import kotlinx.coroutines.ensureActive
```

Add this field to `DiscoverUiState`, after `val selected: CellRow? = null,`

```kotlin
    /**
     * The selected cell in each window that has been read. The current window comes with the
     * selection, the other two follow from one read each, for that one cell.
     */
    val selectedWindows: Map<FeedWindow, CellRow?> = emptyMap(),
```

Add after the `DiscoverUiState` class

```kotlin
/** The selected cell's summary for [property], three windows, headline the 24 hour mean. Null with no selection. */
fun DiscoverUiState.selectedSummary(property: FeedProperty): StationSummary? {
    selected ?: return null
    return PublicSummary.of(property.id, property.unit, selectedWindows)
}
```

Add to the controller's private fields, after `private var restyleJob: Job? = null`

```kotlin
    private var windowsJob: Job? = null

    /** The cell, window and resolution [windowsJob] read for, so a pan does not read them again. */
    private var windowsFor: Triple<Long, FeedWindow, Int>? = null
```

Replace `selectCell` with

```kotlin
    fun selectCell(hex: String?) {
        val row = hex?.let { h -> _state.value.rows.firstOrNull { H3Index.toHex(it.h3) == h } }
        if (row?.h3 == _state.value.selected?.h3) return
        // The selected outline is a filter on its own layer, so the GeoJSON is not rebuilt.
        _state.update { s -> s.copy(selected = row, selectedWindows = if (row == null) emptyMap() else mapOf(s.window to row)) }
        readWindows(row)
    }
```

In `load`, replace

```kotlin
        val selected = current.selected?.let { s -> rows.firstOrNull { it.h3 == s.h3 } }
```

with

```kotlin
        val selected = current.selected?.let { s -> rows.firstOrNull { it.h3 == s.h3 } }
        val sameWindows = selected != null && windowsFor == Triple(selected.h3, window, res)
```

and inside its `_state.update { it.copy(...) }` replace `selected = selected,` with

```kotlin
                selected = selected,
                selectedWindows = when {
                    selected == null -> emptyMap()
                    sameWindows -> it.selectedWindows + (window to selected)
                    else -> mapOf(window to selected)
                },
```

then, directly after that `_state.update { ... }` block and before `restyle(property?.id)`, add

```kotlin
        if (!sameWindows) readWindows(selected)
```

Add this private function before `restyle`

```kotlin
    /**
     * Reads [row]'s cell in the two windows not on screen, one small read each, the same anonymous
     * feed the map uses. A newer selection cancels it, and a result for an older cell, window or
     * resolution is dropped, so it never lands on the cell selected since.
     */
    private fun readWindows(row: CellRow?) {
        windowsJob?.cancel()
        windowsJob = null
        windowsFor = null
        row ?: return
        val p = partition ?: return
        val current = _state.value
        val window = current.window
        val res = current.res
        windowsFor = Triple(row.h3, window, res)
        windowsJob = scope.launch {
            val read = withContext(io) {
                FeedWindow.entries.filter { it != window }.associateWith { w ->
                    runCatching { feed.cells(p, w, res, listOf(row.h3..row.h3)).rows.firstOrNull { it.h3 == row.h3 } }.getOrNull()
                }
            }
            ensureActive()
            _state.update {
                if (it.selected?.h3 != row.h3 || it.window != window || it.res != res) return@update it
                it.copy(selectedWindows = it.selectedWindows + read)
            }
        }
    }
```

Note on ordering in `load`. `res` is written to state in the same `_state.update` before `readWindows` runs, so `readWindows` reads the new resolution.

- [ ] **Step 5. Run it and watch it pass.**

Run `./gradlew --no-daemon :feature:discover:testAndroidHostTest --tests 'earth.walkthru.opensensor.discover.DiscoverControllerTest'`.
Expected. `BUILD SUCCESSFUL`, 4 tests pass.

Then run `./gradlew --no-daemon :feature:discover:iosSimulatorArm64Test`.
Expected. `BUILD SUCCESSFUL`, the same 4 tests pass on iOS.

- [ ] **Step 6. Commit.**

```bash
git add feature/discover/build.gradle.kts feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverController.kt feature/discover/src/commonTest/kotlin/earth/walkthru/opensensor/discover/DiscoverControllerTest.kt
git commit -m "Read the selected cell in all three windows"
```

---

### Task 6. Step blocks and step bands for the Simple spread

**Files**
- Modify `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/CellChartMath.kt`
- Test `feature/discover/src/androidHostTest/kotlin/earth/walkthru/opensensor/discover/CellChartMathTest.kt` (modify)

**Interfaces**
- Consumes `HealthScale.stepOf(value)`, `HealthScale.rangeOf(step): Pair<Double?, Double?>`, `ValueBand(low, high, upperInclusive)`, `MoodBlock`.
- Produces
  - `internal fun stepBlocks(values: List<Double>, health: HealthScale): List<MoodBlock>` (six blocks in `WhoStep` order, weight = area count)
  - `internal fun stepBand(health: HealthScale, step: WhoStep): ValueBand`

- [ ] **Step 1. Write the failing test.** Add to `CellChartMathTest` (add `import earth.walkthru.aqsync.airquality.MoodBlock` to its imports)

```kotlin
    @Test
    fun stepBlocksCountAreasPerStepAndSkipMissingValues() {
        val blocks = stepBlocks(listOf(3.0, 15.0, 15.1, 40.0, 200.0, Double.NaN), WhoGuidelines.PM25)
        assertEquals(WhoStep.entries.toList(), blocks.map { it.step })
        assertEquals(listOf(2f, 1f, 0f, 1f, 0f, 1f), blocks.map { it.weight })
    }

    @Test
    fun aStepBandHoldsExactlyTheValuesOfThatStep() {
        val pm = WhoGuidelines.PM25
        val guideline = stepBand(pm, WhoStep.GUIDELINE)
        assertTrue(0.0 in guideline && 15.0 in guideline)
        assertTrue(15.1 !in guideline)
        val it4 = stepBand(pm, WhoStep.IT4)
        assertTrue(15.1 in it4 && 25.0 in it4 && 15.0 !in it4 && 25.1 !in it4)
        val top = stepBand(pm, WhoStep.ABOVE_IT1)
        assertTrue(75.1 in top && 10_000.0 in top && 75.0 !in top)
        listOf(0.0, 15.0, 15.1, 37.5, 50.0, 74.9, 75.0, 75.1, 500.0).forEach { v ->
            assertEquals(pm.stepOf(v), WhoStep.entries.single { v in stepBand(pm, it) }, "value $v")
        }
    }
```

- [ ] **Step 2. Run it and watch it fail.**

Run `./gradlew --no-daemon :feature:discover:testAndroidHostTest --tests 'earth.walkthru.opensensor.discover.CellChartMathTest'`.
Expected. Compilation fails with `Unresolved reference 'stepBlocks'`.

- [ ] **Step 3. Implement.** In `CellChartMath.kt` add imports `earth.walkthru.aqsync.airquality.HealthScale` and `earth.walkthru.aqsync.airquality.MoodBlock`, then append

```kotlin
/**
 * The Simple spread. Six blocks, one per WHO step in order, each as wide as the number of areas in
 * that step. Non finite values are not counted. A block of zero areas is not drawn.
 */
internal fun stepBlocks(values: List<Double>, health: HealthScale): List<MoodBlock> {
    val finite = values.filter { it.isFinite() }
    return WhoStep.entries.map { step -> MoodBlock(step, finite.count { health.stepOf(it) == step }.toFloat()) }
}

/**
 * The values of [step], as the map and the list filter them while its block is selected. Lower
 * bound exclusive, upper inclusive, open at both ends of the scale, the same reading as [HealthScale.stepOf].
 */
internal fun stepBand(health: HealthScale, step: WhoStep): ValueBand {
    val (low, high) = health.rangeOf(step)
    return ValueBand(low ?: Double.NEGATIVE_INFINITY, high ?: Double.POSITIVE_INFINITY, upperInclusive = true)
}
```

- [ ] **Step 4. Run it and watch it pass.**

Run `./gradlew --no-daemon :feature:discover:testAndroidHostTest --tests 'earth.walkthru.opensensor.discover.CellChartMathTest'`.
Expected. `BUILD SUCCESSFUL`, the two new tests pass with the existing ones.

- [ ] **Step 5. Commit.**

```bash
git add feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/CellChartMath.kt feature/discover/src/androidHostTest/kotlin/earth/walkthru/opensensor/discover/CellChartMathTest.kt
git commit -m "Count areas per mood for the Simple spread"
```

---

### Task 7. DiscoverScreen takes the detail level

**Files**
- Modify `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverScreen.kt`
- Modify `app/src/main/java/earth/walkthru/aqsync/ui/discover/DiscoverRoute.kt`
- Modify `ios/shared/src/iosMain/kotlin/earth/walkthru/opensensor/AppRoot.kt`

**Interfaces**
- Consumes `enum class DetailLevel { SIMPLE, DETAILED, DEVELOPER }` (`earth.walkthru.aqsync.settings`), `AppSettings.detailLevel: Flow<DetailLevel>`, the `level` value `AppRoot` already holds.
- Produces
  - `@Composable fun DiscoverScreen(controller: DiscoverController, networkAllowed: Boolean, level: DetailLevel, onAddSensor: () -> Unit, modifier: Modifier = Modifier, infoButton: @Composable (InfoText, Modifier) -> Unit = { info, m -> DefaultInfoButton(info, m) }, units: UnitSystem = UnitSystem.METRIC)`
  - `DiscoverViewModel.level: StateFlow<DetailLevel>`
  - `private fun DiscoverEntry(networkAllowed: Boolean, level: DetailLevel, units: UnitSystem, onAddSensor: () -> Unit)` in `AppRoot.kt`

This task only threads the value through, so its check is compilation of all three hosts. The behaviour is tested in Task 8.

- [ ] **Step 1. Shared screen.** In `DiscoverScreen.kt` add `import earth.walkthru.aqsync.settings.DetailLevel`. Add the parameter `level: DetailLevel,` to `DiscoverScreen` right after `networkAllowed: Boolean,`, and extend the KDoc with the sentence "[level] is the host's Settings detail level. Simple speaks in moods, Detailed and Developer show the WHO scale." Change the call to

```kotlin
        DiscoverContent(controller, strings, networkAllowed, level == DetailLevel.SIMPLE, onAddSensor, modifier, infoButton)
```

and add the parameter `simple: Boolean,` to `DiscoverContent` right after `networkAllowed: Boolean,`. Task 8 uses it.

- [ ] **Step 2. Android.** In `DiscoverRoute.kt` add `import earth.walkthru.aqsync.data.settings.DetailLevel`. In `DiscoverViewModel`, after `showPublicSensors`, add

```kotlin
    /** Simple until the saved level is read, so nothing more technical flashes first. */
    val level: StateFlow<DetailLevel> = settings.detailLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLevel.SIMPLE)
```

In `DiscoverRoute`, after `val showPublic by ...`, add `val level by viewModel.level.collectAsStateWithLifecycle()` and pass `level = level,` after `networkAllowed = showPublic,`.

- [ ] **Step 3. iOS.** In `AppRoot.kt` change the Discover entry to

```kotlin
                    DiscoverEntry(networkAllowed = showPublic, level = level, units = units, onAddSensor = { nav.toMyDevicesRoot() })
```

and `DiscoverEntry` to

```kotlin
private fun DiscoverEntry(networkAllowed: Boolean, level: DetailLevel, units: UnitSystem, onAddSensor: () -> Unit) {
    val controller = viewModel { DiscoverHolder() }.controller
    DiscoverScreen(
        controller = controller,
        networkAllowed = networkAllowed,
        level = level,
        onAddSensor = onAddSensor,
        units = units,
    )
}
```

`level` is the `val level = prefs?.detailLevel ?: DetailLevel.SIMPLE` declared above `OpenSensorTheme` in the same function, and `DetailLevel` is already imported.

- [ ] **Step 4. Compile all three hosts.**

Run `./gradlew --no-daemon :feature:discover:compileAndroidMain :app:compileDebugKotlin :ios:shared:compileKotlinIosSimulatorArm64`.
Expected. `BUILD SUCCESSFUL`, apart from a possible unused parameter warning for `simple`.

If the Android target's compile task has another name in this convention plugin, list it with `./gradlew --no-daemon :feature:discover:tasks --all | grep -i compile` and use the Android main Kotlin compile task.

- [ ] **Step 5. Commit.**

```bash
git add feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverScreen.kt app/src/main/java/earth/walkthru/aqsync/ui/discover/DiscoverRoute.kt ios/shared/src/iosMain/kotlin/earth/walkthru/opensensor/AppRoot.kt
git commit -m "Pass the detail level to Discover"
```

---

### Task 8. Simple Discover. Mood legend, mood spread, mood cell sheet

**Files**
- Modify `feature/discover/src/commonMain/composeResources/values/strings.xml`, `values-ar/strings.xml`
- Modify `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverStrings.kt`
- Modify `feature/discover/src/commonMain/kotlin/earth/walkthru/opensensor/discover/DiscoverScreen.kt`
- Test `ui/strings/src/androidHostTest/kotlin/earth/walkthru/opensensor/strings/SimpleCopyTest.kt` (modify)

**Interfaces**
- Consumes `MoodStrip`, `MoodLegend`, `moodWord`, `moodLine`, `notAvailableText`, `peacockExpressionFor(step)`, `PeacockIcon` (Task 3), `selectedSummary` (Task 5), `stepBlocks`, `stepBand` (Task 6), `simple: Boolean` in `DiscoverContent` (Task 7), `DiscoverStrings.areaCount(n)`, `window7Days`, `window24Hours`, `windowLastHour`, `notMeasured`, `sampleData`, `sampleInfo`, `formatValue(value, property, units)`, `unitLabel(property, units)`, `freshness(...)`.
- Produces
  - `DiscoverStrings.moodInfo: InfoText`
  - private composables `SimpleLegend(property: FeedProperty, strings: DiscoverStrings, infoButton: @Composable (InfoText, Modifier) -> Unit)`, `SimpleSpread(values: List<Double>, health: HealthScale, strings: DiscoverStrings, highlight: ValueBand?, onHighlight: (ValueBand?) -> Unit)`, `SimpleCellDetail(row: CellRow, state: DiscoverUiState, strings: DiscoverStrings, infoButton: @Composable (InfoText, Modifier) -> Unit, reference: Long, onShowProperty: (String) -> Unit)`
  - `SheetContent` and `InView` gain `simple: Boolean`

- [ ] **Step 1. Write the failing test.** Add to `SimpleCopyTest`

```kotlin
    @Test
    fun simpleDiscoverHasItsMoodInfoSheet() {
        discover.forEach { file ->
            val keys = Catalogues.parse(file).keys
            listOf("discover_mood_info_title", "discover_mood_info1", "discover_mood_info2").forEach { k ->
                assertTrue(k in keys, "${file.parentFile.name} lacks $k")
            }
        }
    }
```

- [ ] **Step 2. Run it and watch it fail.**

Run `./gradlew --no-daemon :ui:strings:testAndroidHostTest --tests 'earth.walkthru.opensensor.strings.SimpleCopyTest'`.
Expected. `simpleDiscoverHasItsMoodInfoSheet` fails with `values lacks discover_mood_info_title`.

- [ ] **Step 3. Add the strings.** In `feature/discover/src/commonMain/composeResources/values/strings.xml`, after the `discover_who_step_spoken` line, add

```xml
    <!-- The Simple legend info sheet. Simple never names the guideline body. -->
    <string name="discover_mood_info_title">Air mood</string>
    <string name="discover_mood_info1">Each colour is a mood of the air, from fresh air to thick smoke. A colour means the same in every place and every view.</string>
    <string name="discover_mood_info2">Choose Detailed in Settings to see the health guideline and the numbers behind each colour.</string>
```

In `values-ar/strings.xml`, after its `discover_who_step_spoken` line, add

```xml
    <string name="discover_mood_info_title">حالة الهواء</string>
    <string name="discover_mood_info1">كل لون حالة للهواء، من الهواء النقي إلى الدخان الكثيف. اللون يعني الشيء نفسه في كل مكان وكل عرض.</string>
    <string name="discover_mood_info2">اختر مستوى التفاصيل في الإعدادات لترى الإرشاد الصحي والأرقام وراء كل لون.</string>
```

- [ ] **Step 4. Run the copy tests and watch them pass.**

Run `./gradlew --no-daemon :ui:strings:testAndroidHostTest`.
Expected. `BUILD SUCCESSFUL`. `SimpleCopyTest` (including the WHO scan over `discover_mood_*`), `CopyLintTest` and `TranslationsTest` pass.

- [ ] **Step 5. Expose the info sheet.** In `DiscoverStrings.kt` add imports for `Res.string.discover_mood_info_title`, `discover_mood_info1`, `discover_mood_info2` (`earth.walkthru.opensensor.discover.resources.*` style, one import each). Add the constructor field after `val whoInfo: InfoText,`

```kotlin
    /** The legend info sheet at the Simple level. No guideline body, no numbers. */
    val moodInfo: InfoText,
```

and in `discoverStrings()` after the `whoInfo = InfoText(...)` argument add

```kotlin
    moodInfo = InfoText(
        stringResource(Res.string.discover_mood_info_title),
        listOf(stringResource(Res.string.discover_mood_info1), stringResource(Res.string.discover_mood_info2)),
    ),
```

- [ ] **Step 6. Simple legend.** In `DiscoverScreen.kt` add imports

```kotlin
import earth.walkthru.aqsync.airquality.HealthScale
import earth.walkthru.opensensor.designsystem.MoodLegend
import earth.walkthru.opensensor.designsystem.MoodStrip
import earth.walkthru.opensensor.designsystem.moodLine
import earth.walkthru.opensensor.designsystem.moodWord
import earth.walkthru.opensensor.designsystem.notAvailableText
import earth.walkthru.opensensor.designsystem.peacockExpressionFor
```

In `DiscoverContent` replace

```kotlin
                    if (property != null && scale != null) {
                        Legend(property, scale, state.window, strings, infoButton)
                    }
```

with

```kotlin
                    if (property != null && scale != null) {
                        // Simple shows mood words for a health scale. A property without one keeps its gradient.
                        if (simple && scale is CellScale.Health) SimpleLegend(property, strings, infoButton) else Legend(property, scale, state.window, strings, infoButton)
                    }
```

and add after the `Legend` composable

```kotlin
/** The Simple legend. The six moods with their colours, the property as title, no numbers. */
@Composable
private fun SimpleLegend(property: FeedProperty, strings: DiscoverStrings, infoButton: @Composable (InfoText, Modifier) -> Unit) {
    FloatingSurface {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            MoodLegend(Modifier.width(212.dp).padding(vertical = 8.dp), title = property.label)
            infoButton(strings.moodInfo, Modifier.size(40.dp))
        }
    }
}
```

- [ ] **Step 7. Thread `simple` into the sheet.** In `DiscoverContent`'s `SheetContent(...)` call add `simple = simple,` after `networkAllowed = networkAllowed,`. In the `onMapClick` lambda add `highlight = null` after `clearSignal++`, so a selected mood block lets go like a pinned bar. Change `SheetContent`'s signature to add `simple: Boolean,` after `networkAllowed: Boolean,`, and its `when` to

```kotlin
        when {
            !state.available -> DiscoverStateMessage(strings.unavailable, PeacockExpression.CONCERNED)
            showAll -> AllProperties(state, strings, onPickProperty)
            state.selected != null && simple -> SimpleCellDetail(state.selected, state, strings, infoButton, reference, onShowProperty)
            state.selected != null -> CellDetail(state.selected, state, strings, infoButton, reference, clearSignal, onHighlight, onShowProperty)
            state.firstRun -> FirstRunCard(strings, onAddSensor, onExplore)
            else -> InView(state, strings, networkAllowed, simple, reference, highlight, clearSignal, onHighlight, onPickCell)
        }
```

- [ ] **Step 8. Simple In view.** Add `simple: Boolean,` to `InView` after `networkAllowed: Boolean,`. Replace its chart block

```kotlin
    if (property != null && values.size >= 2) {
        ChartTitle(property, strings, state)
        Spacer(Modifier.height(8.dp))
        SpreadChart(values, property, state.scale, strings, clearSignal = clearSignal, onHighlight = onHighlight)
        Spacer(Modifier.height(12.dp))
    }
```

with

```kotlin
    val health = (state.scale as? CellScale.Health)?.scale
    if (property != null && values.size >= 2) {
        if (!simple) {
            ChartTitle(property, strings, state)
            Spacer(Modifier.height(8.dp))
            SpreadChart(values, property, state.scale, strings, clearSignal = clearSignal, onHighlight = onHighlight)
            Spacer(Modifier.height(12.dp))
        } else if (health != null) {
            // Simple. Areas per mood, no axis. A property without a health scale shows no chart, no mood is invented.
            ChartTitle(property, strings, state)
            Spacer(Modifier.height(8.dp))
            SimpleSpread(values, health, strings, highlight, onHighlight)
            Spacer(Modifier.height(12.dp))
        }
    }
```

In the list row replace

```kotlin
                    if (step != null) Text(strings.stepName(step), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
```

with

```kotlin
                    if (step != null) Text(if (simple) moodWord(step) else strings.stepName(step), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
```

Add after `InView`

```kotlin
/**
 * Areas in view per mood, as wide as their count. Tapping a block filters the map and the list to
 * that mood, tapping it again lets go. Leaving the list lets go too, like the spread chart.
 */
@Composable
private fun SimpleSpread(values: List<Double>, health: HealthScale, strings: DiscoverStrings, highlight: ValueBand?, onHighlight: (ValueBand?) -> Unit) {
    val shown = stepBlocks(values, health).filter { it.weight > 0f }
    val captions = shown.map { strings.areaCount(it.weight.toInt()) }
    val bands = shown.map { b -> b.step?.let { stepBand(health, it) } }
    val selected = bands.indexOf(highlight).takeIf { highlight != null && it >= 0 }
    val release = rememberUpdatedState(onHighlight)
    DisposableEffect(Unit) { onDispose { release.value(null) } }
    MoodStrip(
        blocks = shown,
        captions = captions,
        selected = selected,
        onBlock = { i -> onHighlight(bands[i]?.takeIf { it != highlight }) },
    )
}
```

Make sure `rememberUpdatedState` and `DisposableEffect` are imported (`androidx.compose.runtime.rememberUpdatedState`, `androidx.compose.runtime.DisposableEffect`). They are already used by `SpreadChart` in this module, so add the imports to `DiscoverScreen.kt` only if this file lacks them.

- [ ] **Step 9. Simple cell sheet.** Add after `CellDetail`

```kotlin
/**
 * The cell at the Simple level. Peacock and mood word beside the 24 hour value, or "n/a", one plain
 * line, then the mood of the last 7 days, 24 hours and hour. A property without a health scale
 * shows its number only. Other measurements are plain value rows.
 */
@Composable
private fun SimpleCellDetail(
    row: CellRow,
    state: DiscoverUiState,
    strings: DiscoverStrings,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    reference: Long,
    onShowProperty: (String) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.isSample) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.sampleData, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                infoButton(strings.sampleInfo, Modifier.size(40.dp))
            }
        }
        val property = state.property
        val units = LocalDiscoverUnits.current
        val summary = property?.let { state.selectedSummary(it) }
        if (property != null && summary != null) {
            val unit = unitLabel(property, units)
            val value = summary.value?.let { formatValue(it, property, units) }
            val shown = value ?: notAvailableText()
            val word = if (summary.scaled) moodWord(summary.step) else null
            val spoken = listOfNotNull(word, property.label, if (value != null) "$value $unit" else shown).joinToString(", ")
            Row(
                Modifier.clearAndSetSemantics {
                    contentDescription = spoken
                    heading()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (summary.scaled) {
                    PeacockIcon(peacockExpressionFor(summary.step), Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    if (word != null) Text(word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Row {
                        Text(shown, style = MaterialTheme.typography.titleLarge, modifier = Modifier.alignByBaseline())
                        if (value != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(property.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                    }
                }
            }
            if (summary.scaled) {
                Spacer(Modifier.height(4.dp))
                Text(moodLine(summary.step), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                MoodStrip(summary.blocks, captions = listOf(strings.window7Days, strings.window24Hours, strings.windowLastHour))
            }
        } else {
            Text(strings.notMeasured, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        val stale = reference - row.updatedEpochMs >= earth.walkthru.aqsync.publicfeed.CellFreshness.STALE_AFTER_MS
        val parts = listOfNotNull(strings.sensors(row.devices), if (stale) strings.stale else freshness(strings, row.updatedEpochMs, reference))
        Text(parts.joinToString("  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val others = state.dictionary.filter { it.id != property?.id }.mapNotNull { p -> row.mean(p.id)?.let { p to it } }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            others.forEach { (p, mean) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onShowProperty(p.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${formatValue(mean, p, units)} ${unitLabel(p, units)}", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
```

- [ ] **Step 10. Run the module tests and the scans.**

Run `./gradlew --no-daemon :feature:discover:testAndroidHostTest :feature:discover:iosSimulatorArm64Test :ui:strings:testAndroidHostTest`.
Expected. `BUILD SUCCESSFUL`. `PresentationLiteralsTest` passes (the new code only templates values, the words come from resources) and `SemanticsLintTest` passes (every new `clickable` passes `role =`).

- [ ] **Step 11. Compile both hosts.**

Run `./gradlew --no-daemon :app:assembleDebug :ios:shared:compileKotlinIosSimulatorArm64`.
Expected. `BUILD SUCCESSFUL`.

- [ ] **Step 12. Commit.**

```bash
git add feature/discover ui/strings/src/androidHostTest/kotlin/earth/walkthru/opensensor/strings/SimpleCopyTest.kt
git commit -m "Speak in moods on Discover at the Simple level"
```

---

### Task 9. Docs and the full gate

**Files**
- Modify `docs/devices-and-discovery.md`
- Modify `docs/screens.md`

**Interfaces**
- Consumes the behaviour of Tasks 1 to 8. Produces no code.

- [ ] **Step 1. Legend scope.** In `docs/devices-and-discovery.md`, in the bullet that starts `- **Legend (WHO, 2026-10-09).**`, change the bold label to `- **Legend (WHO, Detailed and Developer, 2026-10-09).**`. Directly after that bullet add

```markdown
- **Legend (Simple, 2026-10-12).** For PM2.5 and PM10 the floating card shows the six mood
  words with their colours, titled with the property, and no numbers. Its info sheet explains
  the moods without naming the guideline body and points to Detailed in Settings. A property
  without a health scale keeps the gradient legend. The card is one accessibility node that
  reads the title and the six words.
- **In view at Simple (2026-10-12).** The spread chart becomes a mood strip, one block per mood
  as wide as its number of areas, no axis. Tapping a block filters the map and the list to that
  mood, tapping it again or tapping the map lets go. A property without a health scale shows no
  chart at Simple. List rows name the mood word.
- **Cell sheet at Simple (2026-10-12).** Peacock and mood word beside the 24 hour mean, or "n/a"
  when that window has no value, one plain line, then a three block mood strip for 7 days, 24
  hours and the last hour. Selecting a cell reads the other two windows for that one cell from
  the same anonymous feed (`DiscoverController.selectedWindows`), so Show public sensors gates
  them too. Then sensors, freshness and the other measurements as plain value rows.
```

- [ ] **Step 2. Mascot rule.** In the same file replace

```markdown
Either action dismisses it for good. The card carries no numbers, so the mascot rule (never
  beside charts or numbers) holds.
```

with

```markdown
Either action dismisses it for good. The card carries no numbers. The mascot stays away from
  charts and numbers at Detailed and Developer. At Simple the cell sheet places it beside the
  mood word and its one value, the only exception.
```

- [ ] **Step 3. Screens table.** In `docs/screens.md`, in the Destinations table, add a row directly above `| **Devices** (Home) |`, matching that table's column count, with any extra columns left empty

```markdown
| **Discover** | Public H3 map. Simple speaks in moods (mood legend, mood strip of areas, mood cell sheet). Detailed and Developer show the WHO scale. See `devices-and-discovery.md` |
```

- [ ] **Step 4. Check the docs copy.** Run `perl -CSD -ne 'print "$ARGV $.\n" if /\x{2014}/ && /2026-10-12/; close ARGV if eof' docs/devices-and-discovery.md docs/screens.md`.
Expected. No output. The new lines carry no em dash.

- [ ] **Step 5. Run the full gate.**

Run `./gradlew --no-daemon verify`.
Expected. `BUILD SUCCESSFUL`. On a Mac this includes `verifyIos`, the iOS simulator tests and the framework link.

- [ ] **Step 6. Commit.**

```bash
git add docs/devices-and-discovery.md docs/screens.md
git commit -m "Document Simple Discover and the mood strip"
```

- [ ] **Step 7. Phone evidence (not gated).** Install the debug APK on an Android phone and the iOS build on an iPhone, set Settings to Simple, open Discover with Show public sensors on, select a cell. Record in `docs/status.md`, dated, what was seen on each phone (legend words in English and Arabic, strip blocks, n/a where a window is empty, TalkBack and VoiceOver reading the strip as one item). A host or simulator run does not count as this evidence.

---

## Self Review

- **Spec coverage.** Model (Task 1), public builder from 7 d, 24 h, 1 h with 24 h headline (Task 2), `MoodStrip`, `MoodLegend`, `moodWord`, `moodLine`, `peacockExpressionFor(step)`, one node each, hatched n/a, RTL by `Row` (Task 3), mood words moved and duplicates deleted including app and the iOS companion fix (Task 4), controller reads the other two windows on `selectCell` through the same feed (Task 5), level from Android ViewModel and iOS `AppRoot` (Task 7), the Simple legend, In view strip with map and list highlight, and cell sheet, Detailed and Developer unchanged (Tasks 6 and 8), docs (Task 9).
- **Types.** `MoodBlock(step: WhoStep?, weight: Float = 1f)` and `StationSummary.of(propertyId, unit, value, updatedEpochMs, history)` are used with these exact names in Tasks 2, 3, 5, 6 and 8. `selectedWindows: Map<FeedWindow, CellRow?>` and `selectedSummary(property: FeedProperty): StationSummary?` match between Tasks 5 and 8. `stepBlocks(values, health)` and `stepBand(health, step)` match between Tasks 6 and 8. `DiscoverScreen(controller, networkAllowed, level, onAddSensor, ...)` matches the Android and iOS calls in Task 7.

## Deviations From The Spec

- `feature/discover` had no `:core:settings` dependency and no `commonTest` source set. Task 5 adds both.
- The app's `AirMood.titleText()` in `MoodText.kt` had no callers. It is deleted with its strings rather than repointed.
- History's `mood_none` was unused and is deleted.
- Today's `mood_*_advice` strings still name WHO. They are not Simple surfaces in this slice, so `SimpleCopyTest` scans the design system `mood_*` and `value_*` keys and Discover's `discover_mood_*` keys only. Slice 2 replaces the advice.
- "n/a" is the string resource `value_not_available` (English "n/a", Arabic "غير متاح"), because `PresentationLiteralsTest` forbids word literals in feature code.
- The public summary is not stored. The controller keeps `selectedWindows` (property agnostic), and `selectedSummary(property)` builds a `StationSummary` through `PublicSummary.of`, so switching the property chip needs no new read.
- When the screen's window is not 24 hours, the Simple headline shows "n/a" until the 24 hour read for that cell lands, a fraction of a second on the bundled feed. It never shows a value from another window as the 24 hour mean.
- At Simple, a property without a health scale shows no In view chart and no peacock or mood in the cell sheet. Its number still shows.
- The controller has no `networkAllowed`. The window reads go through the same `PublicFeed` the map uses, which the host already gates with Show public sensors.
