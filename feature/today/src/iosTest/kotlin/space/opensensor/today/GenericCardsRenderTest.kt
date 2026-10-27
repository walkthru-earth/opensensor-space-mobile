package space.opensensor.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.protocol.LiveSample
import space.opensensor.model.LocalizedText
import space.opensensor.model.Scale
import space.opensensor.model.ScaleStep
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.metrics_title
import space.opensensor.units.UnitFormatter
import kotlin.test.Test

/** #221. The soil board draws its own hero, metrics card and Station page, never an air word. */
@OptIn(ExperimentalTestApi::class)
class GenericCardsRenderTest {
    private val soilSample = LiveSample.parse("""{"sm":0.31,"st":21.5,"pct":80}""")

    private fun soil(level: DetailLevel) = TodayUiState(TodaySnapshot(name = "Soil", info = SoilFixture.info, live = soilSample), level)

    private fun label(state: TodayUiState, column: String) = state.capabilities.thing!!.datastreams.first { it.name == column }.label("en")

    @Test
    fun theSoilHeroShowsMoistureAtItsDisplayStep() = SoilFixture.served {
        runComposeUiTest {
            val state = soil(DetailLevel.SIMPLE)
            setContent { MaterialTheme { GenericMetricHero(state) } }
            onAllNodesWithText(label(state, SoilFixture.MOISTURE), useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("0.3", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("m3/m3", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText(label(state, SoilFixture.TEMPERATURE), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun theMetricsCardListsTheOtherSoilReadings() = SoilFixture.served {
        runComposeUiTest {
            val state = soil(DetailLevel.DETAILED)
            var title = ""
            setContent {
                MaterialTheme {
                    title = stringResource(Res.string.metrics_title)
                    MetricsCard(state)
                }
            }
            onAllNodesWithText(title, ignoreCase = true, useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText(label(state, SoilFixture.TEMPERATURE), useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("21.5", substring = true, useUnmergedTree = true).assertCountEquals(1)
            // The hero column lives on the hero card.
            onAllNodesWithText(label(state, SoilFixture.MOISTURE), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun theSoilStationPageShowsSoilCardsAndNoAirWording() = SoilFixture.served {
        runComposeUiTest {
            val state = soil(DetailLevel.SIMPLE)
            setContent { MaterialTheme { TodayContent(state, UnitFormatter.Metric, onAction = {}, onRequestFlush = {}) } }
            onAllNodesWithText(label(state, SoilFixture.MOISTURE), useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("PM2.5", substring = true, useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithText("µg/m³", substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun aPublishedScaleNamesItsStepBesideTheColour() = SoilFixture.served {
        val scale = Scale(
            id = "soil-moisture",
            source = "https://example.org/scale",
            steps = listOf(ScaleStep(0.2, LocalizedText(mapOf("en" to "Dry")), "C08040"), ScaleStep(null, LocalizedText(mapOf("en" to "Moist")), "2E7D32")),
        )
        val column = SoilFixture.capabilities().thing!!.primary.first().copy(displayStep = 0.01, scale = scale)
        val reading = SoilFixture.capabilities().reading(soilSample)
        runComposeUiTest {
            setContent { MaterialTheme { HeroValue(column, reading, fresh = true, onCard = Color.Black, quiet = Color.DarkGray) } }
            onAllNodesWithText("0.31", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("Moist", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("Dry", useUnmergedTree = true).assertCountEquals(0)
        }
    }
}
