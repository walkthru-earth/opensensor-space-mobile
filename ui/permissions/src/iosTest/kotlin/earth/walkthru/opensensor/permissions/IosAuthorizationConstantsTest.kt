package earth.walkthru.opensensor.permissions

import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import kotlin.test.Test
import kotlin.test.assertEquals

/** The raw numbers in [IosAuthorization] are the SDK's, so the commonTest mapping holds on iOS. */
class IosAuthorizationConstantsTest {
    @Test
    fun bluetoothConstants() {
        assertEquals(AccessStatus.NOT_DETERMINED, IosAuthorization.bluetooth(CBManagerAuthorizationNotDetermined))
        assertEquals(AccessStatus.RESTRICTED, IosAuthorization.bluetooth(CBManagerAuthorizationRestricted))
        assertEquals(AccessStatus.DENIED, IosAuthorization.bluetooth(CBManagerAuthorizationDenied))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.bluetooth(CBManagerAuthorizationAllowedAlways))
    }

    @Test
    fun locationConstants() {
        assertEquals(AccessStatus.NOT_DETERMINED, IosAuthorization.location(kCLAuthorizationStatusNotDetermined.toLong()))
        assertEquals(AccessStatus.RESTRICTED, IosAuthorization.location(kCLAuthorizationStatusRestricted.toLong()))
        assertEquals(AccessStatus.DENIED, IosAuthorization.location(kCLAuthorizationStatusDenied.toLong()))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.location(kCLAuthorizationStatusAuthorizedAlways.toLong()))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.location(kCLAuthorizationStatusAuthorizedWhenInUse.toLong()))
    }

    @Test
    fun notificationConstants() {
        assertEquals(AccessStatus.NOT_DETERMINED, IosAuthorization.notifications(UNAuthorizationStatusNotDetermined))
        assertEquals(AccessStatus.DENIED, IosAuthorization.notifications(UNAuthorizationStatusDenied))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.notifications(UNAuthorizationStatusAuthorized))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.notifications(UNAuthorizationStatusProvisional))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.notifications(UNAuthorizationStatusEphemeral))
    }

    @Test
    fun cameraConstants() {
        assertEquals(AccessStatus.NOT_DETERMINED, IosAuthorization.camera(AVAuthorizationStatusNotDetermined))
        assertEquals(AccessStatus.RESTRICTED, IosAuthorization.camera(AVAuthorizationStatusRestricted))
        assertEquals(AccessStatus.DENIED, IosAuthorization.camera(AVAuthorizationStatusDenied))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.camera(AVAuthorizationStatusAuthorized))
    }
}
