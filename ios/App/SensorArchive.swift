import Foundation

/// The thing ID rule for the iOS archive. Files are written by the shared Kotlin archive
/// (`IosArchiveStore`), this only checks a thing before Swift stores it with a credential.
enum SensorArchive {
    private static let thingAllowed = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")

    /// `[0-9A-Za-z._-]+`, as the shared archive and SyncWire. The thing is one directory
    /// name, so no slash can pass, and `.` or `..` alone is refused.
    static func isThingId(_ thing: String) -> Bool {
        !thing.isEmpty && thing != "." && thing != ".."
            && thing.unicodeScalars.allSatisfy({ thingAllowed.contains($0) })
    }
}
