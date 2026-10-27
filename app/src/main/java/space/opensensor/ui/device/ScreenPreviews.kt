package space.opensensor.ui.device

import androidx.compose.runtime.Composable
import space.opensensor.data.SyncProgress
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.history.AirMetrics
import space.opensensor.data.history.TodaySoFar
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.DeviceStatus
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.settings.DetailLevel
import space.opensensor.designsystem.OpenSensorTheme
import space.opensensor.device.DeviceConfigUiState
import space.opensensor.today.TodayArchive
import space.opensensor.today.TodayContent
import space.opensensor.today.TodaySnapshot
import space.opensensor.today.TodayUiState
import space.opensensor.ui.components.ThemePreviews
import space.opensensor.units.UnitFormatter

/** Fake state for previews only. No transport, no Room, no ViewModel. */
internal object PreviewFakes {
    val info = DeviceInfo(
        proto = 3,
        fw = "idf-waveshare-parquet-v2.9",
        schema = "waveshare-sim7670g-telemetry-v5",
        cols = 62,
        dict = "50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7",
        thing = "4f1c2a9e-0000-4000-8000-1234567890ab",
        dev = "a1b2c3d4e5f6",
        boot = "0123456789abcdef0123456789abcdef",
        maxRead = 16384,
    )
    private val now = System.currentTimeMillis()
    val live = LiveSample.parse(
        """{"seq":1234,"pms":4,"pm1":6.0,"pm25":9.4,"pm10":12.0,"c25":10.1,"t":24.6,"rh":41.0,""" +
            """"n03":1800,"n05":520,"n1":90,"n25":6,"pct":82,"bat":4012}""",
    )
    val status = DeviceStatus(upSeconds = 86_400, intervalSeconds = 600, buf = 34, fin = 12)

    fun today(level: DetailLevel) = TodayUiState(
        snapshot = TodaySnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = "OS-1A2B",
            connection = ConnectionState.Ready(247),
            transport = TransportKind.BLE,
            info = info,
            status = status,
            statusAtEpochMs = now - 6_000,
            live = live,
            liveAtEpochMs = now - 4_000,
            archive = TodayArchive(files = 142),
            sync = SyncProgress(finishedAtEpochMs = now - 60_000),
            todaySoFar = TodaySoFar(primaryMean = 11.2f, validRows = 3100, coverage = 0.62f, metric = AirMetrics.PM25),
        ),
        level = level,
    )

    fun device(level: DetailLevel) = DeviceUiState(
        address = "AA:BB:CC:DD:EE:FF",
        name = "OS-1A2B",
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
private fun TodaySimplePreview() = OpenSensorTheme { TodayPreview(DetailLevel.SIMPLE) }

@ThemePreviews
@Composable
private fun TodayDeveloperPreview() = OpenSensorTheme { TodayPreview(DetailLevel.DEVELOPER) }

@ThemePreviews
@Composable
private fun DeviceSimplePreview() = OpenSensorTheme { DevicePreview(DetailLevel.SIMPLE) }

@ThemePreviews
@Composable
private fun DeviceDeveloperPreview() = OpenSensorTheme { DevicePreview(DetailLevel.DEVELOPER) }

@Composable
private fun DevicePreview(level: DetailLevel) {
    val state = PreviewFakes.device(level)
    DeviceScreen(state, DeviceConfigUiState(state.toConfigSnapshot(null), level), DeviceExtras(), onAction = {})
}

@Composable
private fun TodayPreview(level: DetailLevel) =
    TodayContent(PreviewFakes.today(level), UnitFormatter.Metric, onAction = {}, onRequestFlush = {})
