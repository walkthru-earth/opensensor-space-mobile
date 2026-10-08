package earth.walkthru.aqsync.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.ArchiveTimeSummary
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.TimedLive
import earth.walkthru.aqsync.data.TimedStatus
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.DeviceCapabilities
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LiveProfiles
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.ThemePreviews
import earth.walkthru.aqsync.ui.device.cards.MetricHeroCard
import earth.walkthru.opensensor.designsystem.OpenSensorTheme

/** Fake state for previews only. No transport, no Room, no ViewModel. */
internal object PreviewFakes {
    val info = DeviceInfo(
        proto = 2,
        fw = "waveshare-0.9.0",
        schema = "waveshare-sim7670g-telemetry-v2",
        cols = 42,
        dict = LiveProfiles.WAVESHARE_V2_DICTIONARY,
        station = "4f1c2a9e-0000-4000-8000-1234567890ab",
        dev = "a1b2c3d4e5f6",
        boot = "0123456789abcdef0123456789abcdef",
        maxRead = 16384,
    )
    private val now = System.currentTimeMillis()
    val live = TimedLive(
        LiveSample(seq = 1234, pms = 4, pm1 = 6.0, pm25 = 9.4, pm10 = 12.0, t = 24.6, rh = 41.0, n03 = 1800, n05 = 520, n1 = 90, n25 = 6, n5 = 1, n10 = 0, pct = 82, chg = 1, bat = 4012),
        now - 4_000,
    )
    val status = TimedStatus(DeviceStatus(upSeconds = 86_400, intervalSeconds = 600, buf = 34, fin = 12), now - 6_000)

    fun today(level: DetailLevel) = TodayUiState(
        address = "AA:BB:CC:DD:EE:FF",
        name = "AQ-1A2B",
        connection = ConnectionState.Ready(247),
        info = info,
        status = status,
        live = live,
        transport = TransportKind.BLE,
        detailLevel = level,
        advancedMode = level.developer,
        archiveTime = ArchiveTimeSummary(files = 142, rows = 85_000, realTimeRows = 85_000),
        sync = SyncProgress(finishedAtEpochMs = now - 60_000),
        todaySoFar = TodaySoFar(pm25Mean = 11.2f, validRows = 3100, coverage = 0.62f),
        capabilities = DeviceCapabilities.from(info),
    )

    fun device(level: DetailLevel) = DeviceUiState(
        address = "AA:BB:CC:DD:EE:FF",
        name = "AQ-1A2B",
        connection = ConnectionState.Ready(247),
        transport = TransportKind.BLE,
        info = info,
        bonded = true,
        detailLevel = level,
        companionSupported = true,
    )
}

@ThemePreviews
@Composable
private fun TodaySimplePreview() = OpenSensorTheme { TodayScreen(PreviewFakes.today(DetailLevel.SIMPLE), onAction = {}) }

@ThemePreviews
@Composable
private fun TodayDeveloperPreview() = OpenSensorTheme { TodayScreen(PreviewFakes.today(DetailLevel.DEVELOPER), onAction = {}) }

@ThemePreviews
@Composable
private fun DeviceSimplePreview() = OpenSensorTheme { DeviceScreen(PreviewFakes.device(DetailLevel.SIMPLE), DeviceExtras(), onAction = {}) }

@ThemePreviews
@Composable
private fun MetricHeroStatesPreview() = OpenSensorTheme {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val now = System.currentTimeMillis()
        MetricHeroCard(LiveSample(pms = 4, pm25 = 9.4), now, DetailLevel.SIMPLE)
        MetricHeroCard(LiveSample(pms = 4, pm25 = 172.0), now, DetailLevel.SIMPLE)
        MetricHeroCard(LiveSample(pms = 1), now, DetailLevel.SIMPLE)
        MetricHeroCard(null, null, DetailLevel.SIMPLE)
    }
}

@ThemePreviews
@Composable
private fun DeviceDeveloperPreview() = OpenSensorTheme { DeviceScreen(PreviewFakes.device(DetailLevel.DEVELOPER), DeviceExtras(), onAction = {}) }
