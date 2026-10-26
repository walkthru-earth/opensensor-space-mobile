package space.opensensor.permissions

/**
 * The iOS authorization values mapped onto [AccessStatus]. They take the raw enum values so the
 * mapping is tested on the host in commonTest. The iOS test (IosAuthorizationConstantsTest) checks
 * these numbers against the SDK constants.
 *
 * - CBManagerAuthorization https://developer.apple.com/documentation/corebluetooth/cbmanagerauthorization
 * - CLAuthorizationStatus https://developer.apple.com/documentation/corelocation/clauthorizationstatus
 * - UNAuthorizationStatus https://developer.apple.com/documentation/usernotifications/unauthorizationstatus
 * - AVAuthorizationStatus https://developer.apple.com/documentation/avfoundation/avauthorizationstatus
 * - Local Network has no status API (TN3179), only the browser's PolicyDenied.
 */
object IosAuthorization {
    const val NOT_DETERMINED = 0L
    const val RESTRICTED = 1L
    const val DENIED = 2L

    /** CBManagerAuthorization. 3 is allowedAlways. */
    const val BLUETOOTH_ALLOWED_ALWAYS = 3L

    /** CLAuthorizationStatus. 3 is authorizedAlways, 4 authorizedWhenInUse. */
    const val LOCATION_ALWAYS = 3L
    const val LOCATION_WHEN_IN_USE = 4L

    /** UNAuthorizationStatus. 0 notDetermined, 1 denied, 2 authorized, 3 provisional, 4 ephemeral. */
    const val NOTIFICATIONS_DENIED = 1L

    /** AVAuthorizationStatus. 3 is authorized. */
    const val CAMERA_AUTHORIZED = 3L

    fun bluetooth(raw: Long): AccessStatus = when (raw) {
        NOT_DETERMINED -> AccessStatus.NOT_DETERMINED
        RESTRICTED -> AccessStatus.RESTRICTED
        DENIED -> AccessStatus.DENIED
        BLUETOOTH_ALLOWED_ALWAYS -> AccessStatus.GRANTED
        else -> AccessStatus.UNKNOWN
    }

    fun location(raw: Long): AccessStatus = when (raw) {
        NOT_DETERMINED -> AccessStatus.NOT_DETERMINED
        RESTRICTED -> AccessStatus.RESTRICTED
        DENIED -> AccessStatus.DENIED
        LOCATION_ALWAYS, LOCATION_WHEN_IN_USE -> AccessStatus.GRANTED
        else -> AccessStatus.UNKNOWN
    }

    fun notifications(raw: Long): AccessStatus = when (raw) {
        NOT_DETERMINED -> AccessStatus.NOT_DETERMINED
        NOTIFICATIONS_DENIED -> AccessStatus.DENIED
        in 2L..4L -> AccessStatus.GRANTED
        else -> AccessStatus.UNKNOWN
    }

    /** Only a browser waiting with kDNSServiceErr_PolicyDenied is a denial. Anything else is unknown. */
    fun localNetwork(policyDenied: Boolean): AccessStatus = if (policyDenied) AccessStatus.DENIED else AccessStatus.UNKNOWN

    /** The camera for the sticker scan. Reading it never shows the prompt. */
    fun camera(raw: Long): AccessStatus = when (raw) {
        NOT_DETERMINED -> AccessStatus.NOT_DETERMINED
        RESTRICTED -> AccessStatus.RESTRICTED
        DENIED -> AccessStatus.DENIED
        CAMERA_AUTHORIZED -> AccessStatus.GRANTED
        else -> AccessStatus.UNKNOWN
    }
}
