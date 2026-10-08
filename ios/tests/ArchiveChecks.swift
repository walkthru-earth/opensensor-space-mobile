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
            "UPPER.parquet", "a..b.parquet", String(repeating: "a", count: 392) + ".parquet",
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
        try completedAndExportChecks(bytes: bytes, crc: crc, station: station, name: name)
        print("iOS archive: resume, receipt reconstruction/integrity, export snapshot, offset, CRC, immutable conflict, path and symlink checks passed")
    }

    private static func completedAndExportChecks(bytes: Data, crc: UInt32, station: String, name: String) throws {
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

        // Use a clean source tree: exports preserve both station and Hive directories.
        let exportRoot = fixture.appendingPathComponent("archive")
        let exportArchive = try SensorArchive(root: exportRoot)
        let exportedFinal = try finalize(exportArchive, station: station, name: name)
        _ = try finalize(exportArchive, station: "other-station", name: "station=other-station/day=2026-10-08/data.parquet")
        let partial = try exportArchive.prepare(station: station, name: "unfinished.parquet", size: size, crc: crc)!
        try partial.append(bytes.prefix(5), at: 0, windowEnd: size)
        try partial.persist()
        let snapshot = fixture.appendingPathComponent("snapshot")
        let exportedCount = try exportArchive.export(to: snapshot)
        precondition(exportedCount == 2)
        let entries = try FileManager.default.subpathsOfDirectory(atPath: snapshot.path)
        let parquetPaths = Set(entries.filter { $0.hasSuffix(".parquet") })
        precondition(parquetPaths == Set([
            "\(station)/\(name)",
            "other-station/station=other-station/day=2026-10-08/data.parquet",
        ]))
        precondition(!entries.contains { $0.hasSuffix(".partial") || $0.hasSuffix(".verified.json") })
        precondition(FileManager.default.fileExists(atPath: partial.partial.path))
        for path in parquetPaths {
            let copied = try Data(contentsOf: snapshot.appendingPathComponent(path))
            precondition(copied == bytes)
        }
        // The snapshot owns independent copies even if the local source subsequently changes.
        try FileManager.default.removeItem(at: exportedFinal)
        let independentCopy = try Data(contentsOf: snapshot.appendingPathComponent("\(station)/\(name)"))
        precondition(independentCopy == bytes)

        let symlinkArchive = try SensorArchive(root: fixture.appendingPathComponent("symlink-archive"))
        try FileManager.default.createSymbolicLink(
            atPath: symlinkArchive.root.appendingPathComponent("unrelated-link").path,
            withDestinationPath: exportRoot.path)
        do {
            _ = try symlinkArchive.export(to: fixture.appendingPathComponent("unsafe-snapshot"))
            fatalError("archive export accepted a symlink")
        } catch SensorArchive.Failure.unsafePath {}
    }
}
