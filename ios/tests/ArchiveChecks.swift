import Foundation

/// Thing ID checks shared by the host harness (verify-native.sh) and the Xcode test target.
enum ArchiveChecks {
    static func run() throws {
        for thing in ["11223344-5566-7788-99aa-bbccddeeff00", "aq.lab_1-b", "v2.0", "a..b"] {
            precondition(SensorArchive.isThingId(thing))
        }
        for unsafe in ["", ".", "..", "a/b", "../x", "a\\b", "a b", "é"] {
            precondition(!SensorArchive.isThingId(unsafe))
        }
        print("iOS archive: thing id checks passed")
    }
}
