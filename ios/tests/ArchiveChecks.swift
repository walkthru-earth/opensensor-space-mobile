import Foundation

/// Station ID checks shared by the host harness (verify-native.sh) and the Xcode test target.
enum ArchiveChecks {
    static func run() throws {
        for station in ["11223344-5566-7788-99aa-bbccddeeff00", "aq.lab_1-b", "v2.0", "a..b"] {
            precondition(SensorArchive.isStationId(station))
        }
        for unsafe in ["", ".", "..", "a/b", "../x", "a\\b", "a b", "é"] {
            precondition(!SensorArchive.isStationId(unsafe))
        }
        print("iOS archive: station id checks passed")
    }
}
