import Foundation

/// The station ID rule for the iOS archive. Files are written by the shared Kotlin archive
/// (`IosArchiveStore`), this only checks a station before Swift stores it with a credential.
enum SensorArchive {
    private static let stationAllowed = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")

    /// `[0-9A-Za-z._-]+`, as the shared archive and SyncWire. The station is one directory
    /// name, so no slash can pass, and `.` or `..` alone is refused.
    static func isStationId(_ station: String) -> Bool {
        !station.isEmpty && station != "." && station != ".."
            && station.unicodeScalars.allSatisfy({ stationAllowed.contains($0) })
    }
}
