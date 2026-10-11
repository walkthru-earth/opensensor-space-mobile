import CoreBluetooth
import Foundation

/// Tells a broken bond from an ordinary Bluetooth loss. A sensor whose bonds were reset refuses the
/// keys this phone still holds, and only removing the pairing in iOS Settings fixes it, so the
/// connection ends without a retry.
enum BleBondFailure {
    /// True when Core Bluetooth says the pairing keys no longer work for this sensor.
    static func matches(_ error: Error?) -> Bool {
        guard let error else { return false }
        let ns = error as NSError
        switch ns.domain {
        case CBErrorDomain:
            return ns.code == CBError.Code.peerRemovedPairingInformation.rawValue
                || ns.code == CBError.Code.encryptionTimedOut.rawValue
        case CBATTErrorDomain:
            return ns.code == CBATTError.Code.insufficientEncryption.rawValue
                || ns.code == CBATTError.Code.insufficientAuthentication.rawValue
        default:
            return false
        }
    }
}
