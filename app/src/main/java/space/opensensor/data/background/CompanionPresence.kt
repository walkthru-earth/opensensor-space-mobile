package space.opensensor.data.background

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import space.opensensor.data.local.database.KnownDevice
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/** Ends the "wake when nearby" association when a device is forgotten. */
fun interface CompanionUnlinker {
    fun disassociate(address: String, associationId: Int?)
}

/**
 * Companion Device Manager: the system-side "is my logger nearby?" watcher behind Auto-sync's
 * *wake when nearby* — the mechanism the firmware repo's `docs/background-sync-triggers.md` puts
 * first, because it needs nothing from the device but a stable BLE address.
 *
 * What the framework does once a device is associated and observed (AOSP `android16-release`,
 * `services/companion/.../devicepresence/`): it runs one low-power BLE scan filtered on the
 * device's **MAC address** (`FIRST_MATCH | MATCH_LOST`), binds [CompanionPresenceService] while the
 * device is seen, and — because the manifest declares `REQUEST_COMPANION_RUN_IN_BACKGROUND` and
 * `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND` — puts this package on the power-save allowlist and
 * allows background data **for as long as the device is present**. That exemption is what the
 * measured screen-off socket kill ("Destroyed live tcp sockets for uids=…", 2026-09-17) called for;
 * the foreground service remains as belt and braces. None of it is bench-verified yet.
 *
 * Association is a one-time system dialog that must be launched from an Activity, so the flow is
 * split: [associate] hands back an [IntentSender]; the screen launches it; [onAssociated] records
 * the result and starts observing. The device must be **advertising** for the dialog to find it —
 * a device we hold a BLE session with is not (one connection at a time), so the caller drops a BLE
 * session first (a LAN session is fine). API shapes: 31–32 report addresses only
 * ([KnownDevice.COMPANION_ASSOCIATED_LEGACY]); 33+ give an `AssociationInfo` with an id; 36+ observe
 * through [ObservingDevicePresenceRequest] and deliver `DevicePresenceEvent`s.
 */
@Singleton
class CompanionPresence @Inject constructor(@ApplicationContext private val context: Context) : CompanionUnlinker {
    private val manager: CompanionDeviceManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            context.getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }

    /** False on a device without the companion-device feature (rare; some TVs and tablets). */
    val supported: Boolean get() = manager != null

    /**
     * The association the framework holds for [address], if any — the truth, as opposed to the
     * Room column, which is our memory of it. Null id on API 31–32 (address-only API).
     */
    fun associationFor(address: String): Int? {
        val cdm = manager ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.myAssociations.firstOrNull { it.macMatches(address) }?.id
            } else {
                @Suppress("DEPRECATION")
                if (cdm.associations.any { it.equals(address, ignoreCase = true) }) KnownDevice.COMPANION_ASSOCIATED_LEGACY else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "associationFor: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Ask the system to associate this app with the logger at [address]. The system scans for that
     * exact address and, since the request is single-device, shows a confirm dialog rather than a
     * chooser. [onIntent] receives the sender the Activity must launch; [onError] the framework's
     * reason (discovery timeout when the device is not advertising, user rejection, …).
     */
    fun associate(address: String, executor: Executor, onIntent: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = manager ?: return onError("Companion devices are not supported on this phone")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            // API 33+: the pending dialog.
            override fun onAssociationPending(intentSender: IntentSender) = onIntent(intentSender)

            // API 31–32: same thing, older name.
            @Deprecated("Deprecated in Java")
            override fun onDeviceFound(intentSender: IntentSender) = onIntent(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                // Only ever called on API 33+ (the callback itself is 33+); AssociationInfo.getId is 33+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Log.i(TAG, "association created id=${associationInfo.id}")
            }

            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "association failed")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.associate(request, executor, callback)
            } else {
                @Suppress("DEPRECATION")
                cdm.associate(request, callback, null)
            }
        } catch (e: Exception) {
            onError("${e::class.simpleName}: ${e.message}")
        }
    }

    /**
     * The dialog came back approved: resolve the association for [address] (from the framework, not
     * the result extras, so API 31–32 work too) and start observing presence. Returns the id to
     * store, or null when the framework knows no association after all.
     */
    fun onAssociated(address: String): Int? {
        val id = associationFor(address) ?: return null
        observe(address, id)
        return id
    }

    /** (Re)arm presence observation; idempotent on the framework side. Safe to call at every app start. */
    fun observe(address: String, associationId: Int) {
        val cdm = manager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && associationId != KnownDevice.COMPANION_ASSOCIATED_LEGACY) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(address)
            }
            Log.i(TAG, "observing presence of $address (association $associationId)")
        } catch (e: Exception) {
            Log.w(TAG, "observe $address: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** Forget the association (and stop observing) — "wake when nearby" off, or the device forgotten. */
    override fun disassociate(address: String, associationId: Int?) {
        val cdm = manager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && associationId != null && associationId != KnownDevice.COMPANION_ASSOCIATED_LEGACY) {
                cdm.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.stopObservingDevicePresence(address)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop observing $address: ${e::class.simpleName}: ${e.message}")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && associationId != null && associationId != KnownDevice.COMPANION_ASSOCIATED_LEGACY) {
                cdm.disassociate(associationId)
            } else {
                @Suppress("DEPRECATION")
                cdm.disassociate(address)
            }
            Log.i(TAG, "disassociated $address")
        } catch (e: Exception) {
            Log.w(TAG, "disassociate $address: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** Address for a presence event's association id (API 33+), upper-case like `BluetoothDevice.address`. */
    fun addressFor(associationId: Int): String? {
        val cdm = manager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            cdm.myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()?.uppercase()
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun AssociationInfo.macMatches(address: String): Boolean = deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true

    companion object {
        private const val TAG = "CompanionPresence"
    }
}
