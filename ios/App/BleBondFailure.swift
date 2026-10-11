import CoreBluetooth
import Foundation

/// Tells a broken bond from an ordinary Bluetooth loss. A sensor whose bonds were reset refuses the
/// keys this phone still holds, and only removing the pairing in iOS Settings fixes it, so the
/// connection ends without a retry.
enum BleBondFailure {
    /// What one Core Bluetooth error says about the bond. Read on the delegate side, judged later
    /// against whether this phone ever held a working bond with the sensor.
    enum Signal: Sendable {
        case none
        /// The sensor dropped this phone's pairing keys. Always a broken bond.
        case removed
        /// Encryption or authentication was refused. iOS reports the same when the first pairing
        /// prompt is cancelled, so it means a broken bond only after an earlier encrypted session.
        case refused

        func isBroken(previouslyBonded: Bool) -> Bool {
            switch self {
            case .none: false
            case .removed: true
            case .refused: previouslyBonded
            }
        }
    }

    static func signal(_ error: Error?) -> Signal {
        guard let error else { return .none }
        let ns = error as NSError
        switch ns.domain {
        case CBErrorDomain:
            if ns.code == CBError.Code.peerRemovedPairingInformation.rawValue { return .removed }
            return ns.code == CBError.Code.encryptionTimedOut.rawValue ? .refused : .none
        case CBATTErrorDomain:
            return ns.code == CBATTError.Code.insufficientEncryption.rawValue
                || ns.code == CBATTError.Code.insufficientAuthentication.rawValue ? .refused : .none
        default:
            return .none
        }
    }

    /// True when Core Bluetooth says the pairing keys no longer work for this sensor.
    /// [previouslyBonded] is true once an encrypted read from this peripheral succeeded.
    static func matches(_ error: Error?, previouslyBonded: Bool) -> Bool {
        signal(error).isBroken(previouslyBonded: previouslyBonded)
    }
}
