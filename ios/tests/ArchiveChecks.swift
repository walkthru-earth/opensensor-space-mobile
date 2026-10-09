import Foundation

/// Archive integrity checks shared by the host harness (verify-native.sh) and the Xcode test target.
enum ArchiveChecks {
    static func run() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let archive = try SensorArchive(root: root)
        let station = "11223344-5566-7788-99aa-bbccddeeff00"
        let name = "station=\(station)/unsynced/boot=ab/data.parquet"
        let bytes = Data("PAR1payloadPAR1".utf8)
        // CRC independently calculated with Python zlib: crc32(b'PAR1payloadPAR1').
        let crc: UInt32 = 0xc907_1203
        let download = try archive.prepare(station: station, name: name, size: UInt64(bytes.count), crc: crc)!
        try download.append(bytes.prefix(7), at: 0, windowEnd: UInt64(bytes.count))
        try download.persist()
        let resumed = try archive.prepare(station: station, name: name, size: UInt64(bytes.count), crc: crc)!
        precondition(resumed.offset == 7)
        do {
            try resumed.append(bytes.dropFirst(7), at: 6, windowEnd: UInt64(bytes.count))
            fatalError("unexpected offset accepted")
        } catch SensorArchive.Failure.invalidChunk {}
        try resumed.append(bytes.dropFirst(7), at: 7, windowEnd: UInt64(bytes.count))
        try resumed.commit()
        let duplicate = try archive.prepare(station: station, name: name, size: UInt64(bytes.count), crc: crc)
        precondition(duplicate == nil)
        do {
            _ = try archive.prepare(station: station, name: name, size: UInt64(bytes.count), crc: crc + 1)
            fatalError("immutable conflict accepted")
        } catch SensorArchive.Failure.conflictingArchive {}
        for unsafe in [
            "../escape.parquet", "/escape.parquet", "a//b.parquet", "a/./b.parquet", "a\\b.parquet", "a\0b.parquet",
            "UPPER.parquet", ".parquet", "a..b.parquet", String(repeating: "a", count: 392) + ".parquet",
        ] {
            do {
                _ = try archive.target(station: station, name: unsafe)
                fatalError("unsafe path accepted")
            } catch SensorArchive.Failure.unsafePath {}
        }
        // Exactly 399 UTF-8 bytes is the contract maximum and remains a valid archive path.
        _ = try archive.target(
            station: station, name: String(repeating: "a", count: SensorArchive.maxNameBytes - 8) + ".parquet")
        // The root is excluded from backup when created, without a per-sync attribute write.
        let rootValues = try archive.root.resourceValues(forKeys: [.isExcludedFromBackupKey])
        precondition(rootValues.isExcludedFromBackup == true)
        let link = root.appendingPathComponent(station).appendingPathComponent("link")
        try FileManager.default.createSymbolicLink(atPath: link.path, withDestinationPath: root.path)
        do {
            _ = try archive.target(station: station, name: "link/escape.parquet")
            fatalError("symlink accepted")
        } catch SensorArchive.Failure.unsafePath {}
        let wrongMagic = Data("XXXXpayloadYYYY".utf8)
        let magicFailure = try archive.prepare(
            station: station, name: "bad-magic.parquet", size: UInt64(wrongMagic.count), crc: 2_389_614_836)!
        try magicFailure.append(wrongMagic, at: 0, windowEnd: UInt64(wrongMagic.count))
        do {
            try magicFailure.commit()
            fatalError("bad PAR1 magic accepted")
        } catch SensorArchive.Failure.invalidFile {}
        let bad = try archive.prepare(station: station, name: "bad.parquet", size: UInt64(bytes.count), crc: crc + 1)!
        try bad.append(bytes, at: 0, windowEnd: UInt64(bytes.count))
        do {
            try bad.commit()
            fatalError("CRC mismatch accepted")
        } catch SensorArchive.Failure.invalidFile {}
        precondition(!FileManager.default.fileExists(atPath: bad.partial.path))
        try completedChecks(bytes: bytes, crc: crc, station: station, name: name)
        try stationAndPartialChecks(bytes: bytes, crc: crc)
        print(
            "iOS archive: resume, receipt reconstruction/integrity, offset, CRC, immutable conflict, "
                + "path, symlink, station id and stale partial checks passed")
    }

    /// Station ids follow Android's `[0-9A-Za-z._-]+` (FileArchive.kt:131), and a changed size or CRC
    /// for one name removes the older partial (FileArchive.kt:97-102).
    private static func stationAndPartialChecks(bytes: Data, crc: UInt32) throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let archive = try SensorArchive(root: root)
        for station in ["aq.lab_1-b", "v2.0", "a..b"] {
            precondition(SensorArchive.isStationId(station))
            let path = try archive.target(station: station, name: "data.parquet").path
            precondition(path == root.appendingPathComponent("\(station)/data.parquet").path)
        }
        for unsafe in ["", ".", "..", "a/b", "../x", "a\\b", "a b", "é"] {
            precondition(!SensorArchive.isStationId(unsafe))
            do {
                _ = try archive.target(station: unsafe, name: "data.parquet")
                fatalError("unsafe station accepted")
            } catch SensorArchive.Failure.unsafePath {}
        }

        let size = UInt64(bytes.count)
        let first = try archive.prepare(station: "s", name: "data.parquet", size: size, crc: crc + 1)!
        try first.append(bytes.prefix(4), at: 0, windowEnd: size)
        try first.persist()
        // A longer final name that shares the prefix keeps its own partial.
        let neighbour = try archive.prepare(station: "s", name: "data.parquet.parquet", size: size, crc: crc)!
        precondition(FileManager.default.fileExists(atPath: first.partial.path))
        let second = try archive.prepare(station: "s", name: "data.parquet", size: size, crc: crc)!
        precondition(second.offset == 0)
        precondition(!FileManager.default.fileExists(atPath: first.partial.path))
        precondition(FileManager.default.fileExists(atPath: neighbour.partial.path))
        try second.append(bytes, at: 0, windowEnd: size)
        try second.commit()
        let left = try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("s").path)
        precondition(Set(left) == Set(["data.parquet", "data.parquet.verified.json", neighbour.partial.lastPathComponent]))
    }

    private static func completedChecks(bytes: Data, crc: UInt32, station: String, name: String) throws {
        let fixture = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: fixture) }
        let size = UInt64(bytes.count)
        func finalize(_ archive: SensorArchive, station: String, name: String) throws -> URL {
            let download = try archive.prepare(station: station, name: name, size: size, crc: crc)!
            try download.append(bytes, at: 0, windowEnd: size)
            try download.commit()
            return download.final
        }

        // A fresh archive uses the durable receipt, while legacy finals fall back to OPEN.
        let receiptRoot = fixture.appendingPathComponent("receipts")
        let receiptArchive = try SensorArchive(root: receiptRoot)
        let final = try finalize(receiptArchive, station: station, name: name)
        let receipt = final.appendingPathExtension("verified.json")
        let originalReceipt = try Data(contentsOf: receipt)
        let reconstructed = try SensorArchive(root: receiptRoot)
        let complete = try reconstructed.completed(station: station, name: name, size: size)
        precondition(complete)
        let reconstructedReceipt = try Data(contentsOf: receipt)
        precondition(reconstructedReceipt == originalReceipt)
        try FileManager.default.removeItem(at: receipt)
        let legacyComplete = try reconstructed.completed(station: station, name: name, size: size)
        precondition(!legacyComplete)
        let legacyBytes = try Data(contentsOf: final)
        precondition(legacyBytes == bytes)
        let legacyDownload = try reconstructed.prepare(station: station, name: name, size: size, crc: crc)
        precondition(legacyDownload == nil)
        let upgradedComplete = try reconstructed.completed(station: station, name: name, size: size)
        precondition(upgradedComplete)

        // A conflicting manifest or same-length corruption preserves the finalized file.
        do {
            _ = try reconstructed.completed(station: station, name: name, size: size + 1)
            fatalError("changed manifest size accepted")
        } catch SensorArchive.Failure.conflictingArchive {}
        let afterSizeConflict = try Data(contentsOf: final)
        precondition(afterSizeConflict == bytes)
        var tampered = bytes
        tampered[4] ^= 1
        try tampered.write(to: final)
        do {
            _ = try reconstructed.completed(station: station, name: name, size: size)
            fatalError("same-length archive corruption accepted")
        } catch SensorArchive.Failure.conflictingArchive {}
        let preservedTampered = try Data(contentsOf: final)
        precondition(preservedTampered == tampered)

        let linkedFinal = try finalize(receiptArchive, station: station, name: "linked.parquet")
        let linkedReceipt = linkedFinal.appendingPathExtension("verified.json")
        try FileManager.default.removeItem(at: linkedReceipt)
        try FileManager.default.createSymbolicLink(atPath: linkedReceipt.path, withDestinationPath: receipt.path)
        do {
            _ = try reconstructed.completed(station: station, name: "linked.parquet", size: size)
            fatalError("receipt symlink accepted")
        } catch SensorArchive.Failure.unsafePath {}
    }
}
