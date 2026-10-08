import Foundation

@main struct ArchiveChecks {
    @MainActor static func main() async throws {
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
        try await TransferChecks.run()
        print("iOS archive: resume, offset, CRC, immutable conflict, path and symlink checks passed")
    }
}
