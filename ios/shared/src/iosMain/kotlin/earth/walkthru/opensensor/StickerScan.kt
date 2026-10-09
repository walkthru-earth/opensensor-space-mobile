package earth.walkthru.opensensor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.walkthru.opensensor.device.StickerScan
import earth.walkthru.opensensor.device.StickerScans
import earth.walkthru.opensensor.permissions.AccessDialogTexts
import earth.walkthru.opensensor.permissions.AccessDialogs
import earth.walkthru.opensensor.permissions.AccessHooks
import earth.walkthru.opensensor.permissions.AccessKind
import earth.walkthru.opensensor.permissions.AccessStatus
import earth.walkthru.opensensor.permissions.IosAuthorization
import earth.walkthru.opensensor.permissions.RuntimeAccess
import earth.walkthru.opensensor.permissions.openAppSettings
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.camera_scan_blocked
import earth.walkthru.opensensor.strings.resources.camera_scan_off
import earth.walkthru.opensensor.strings.resources.perm_camera_body
import earth.walkthru.opensensor.strings.resources.perm_camera_settings_body
import earth.walkthru.opensensor.strings.resources.perm_camera_settings_title
import earth.walkthru.opensensor.strings.resources.perm_camera_title
import earth.walkthru.opensensor.strings.resources.sensor_scan_unavailable
import earth.walkthru.opensensor.strings.resources.sensor_scan_unrecognized
import org.jetbrains.compose.resources.stringResource
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Swift's VisionKit sticker scanner (`ios/App/StickerScanner.swift`). It presents the scanner and
 * calls `onResult` once on the main thread with the decoded barcode string, or null when the user
 * cancelled or scanning stopped. It returns false when the scanner could not open. Swift passes null
 * instead of a scanner on hardware without VisionKit data scanning, which hides the Scan button.
 */
typealias ScanSticker = (onResult: (String?) -> Unit) -> Boolean

/** The words the card shows when a scan gives no sticker, resolved at the composable edge. */
internal class StickerScanWords(val unrecognized: String, val unavailable: String, val cameraOff: String, val cameraBlocked: String)

/** The sticker card's scan state on iOS and the tap that starts a scan. */
@Stable
internal class IosStickerScan(private val scanner: ScanSticker?, private val access: RuntimeAccess, private val camera: CameraAccess) {
    /** False on hardware without VisionKit data scanning. The card then hides Scan. */
    val available: Boolean get() = scanner != null

    /** The latest result for the card. A cancel leaves it as it was. */
    var scan by mutableStateOf<StickerScan?>(null)
        private set

    /** Set on every composition, so a scan that answers later uses the current words. */
    var words: StickerScanWords? = null

    /** The Scan tap. Asks for the camera first when iOS never asked. */
    fun start() {
        val scanner = scanner ?: return
        access.run(
            onDeclined = {
                val w = words ?: return@run
                scan = StickerScans.failed(scan, if (camera.status() == AccessStatus.RESTRICTED) w.cameraBlocked else w.cameraOff)
            },
        ) {
            val w = words ?: return@run
            val opened = scanner { raw -> scan = StickerScans.read(scan, raw, w.unrecognized) }
            if (!opened) scan = StickerScans.failed(scan, w.unavailable)
        }
    }
}

/**
 * The Scan button of the sticker card on iOS. Camera access is asked only from that tap
 * ([earth.walkthru.opensensor.permissions.UserAction.SCAN_STICKER]), never at launch. A first refusal
 * is respected with a short note on the card, a later tap after a denial offers the app's page in
 * Settings, and a restricted camera only gets the note because Settings cannot change it. Manual entry
 * stays usable in every case. Only the decoded string reaches Kotlin, and only the parsed value
 * fills the card's field.
 */
@Composable
internal fun rememberIosStickerScan(scanSticker: ScanSticker?): IosStickerScan {
    val access = remember { RuntimeAccess(AccessKind.CAMERA) }
    val camera = remember(access) { CameraAccess(onAnswer = access::onPermissionResult) }
    access.bind(AccessHooks.statusBased(status = camera::status, requestPermission = camera::request, openSettings = ::openAppSettings))
    AccessDialogs(access, cameraAccessTexts())
    val holder = remember(scanSticker) { IosStickerScan(scanSticker, access, camera) }
    holder.words = StickerScanWords(
        unrecognized = stringResource(Res.string.sensor_scan_unrecognized),
        unavailable = stringResource(Res.string.sensor_scan_unavailable),
        cameraOff = stringResource(Res.string.camera_scan_off),
        cameraBlocked = stringResource(Res.string.camera_scan_blocked),
    )
    return holder
}

@Composable
private fun cameraAccessTexts(): AccessDialogTexts = AccessDialogTexts(
    rationaleTitle = stringResource(Res.string.perm_camera_title),
    rationaleBody = stringResource(Res.string.perm_camera_body),
    settingsTitle = stringResource(Res.string.perm_camera_settings_title),
    settingsBody = stringResource(Res.string.perm_camera_settings_body),
)

/** AVFoundation's video authorization. Reading the status never shows the prompt. */
internal class CameraAccess(private val onAnswer: () -> Unit) {
    fun status(): AccessStatus = IosAuthorization.camera(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo))

    fun request() {
        if (status() != AccessStatus.NOT_DETERMINED) {
            onAnswer()
            return
        }
        // The completion runs on an arbitrary queue. The flow and its state live on the main thread.
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
            dispatch_async(dispatch_get_main_queue()) { onAnswer() }
        }
    }
}
