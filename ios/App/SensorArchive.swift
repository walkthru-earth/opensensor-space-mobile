import Foundation

/// Immutable local copies. Only verified bytes acquire their final .parquet name.
final class SensorArchive {
    enum Failure: Error, LocalizedError {
        case unsafePath, invalidFile, conflictingArchive, invalidChunk
        var errorDescription: String? {
            switch self {
            case .unsafePath: return "The sensor supplied an unsafe archive path."
            case .invalidFile: return "File verification failed (size, CRC-32 or PAR1). Retry sync."
            case .conflictingArchive: return "An existing archive differs; it was preserved."
            case .invalidChunk: return "The sensor sent an unexpected file offset or length."
            }
        }
    }
    private static let crcTable: [UInt32] = (0..<256).map { value in
        var crc = UInt32(value)
        for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb8_8320 : 0) }
        return crc
    }
    let root: URL
    private struct Receipt: Codable {
        let size: UInt64
        let crc: UInt32
    }
    init(root: URL) throws {
        self.root = root.standardizedFileURL
        try FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
    }

    func target(station: String, name: String) throws -> URL {
        let stationAllowed = CharacterSet(
            charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")
        guard !station.isEmpty, station.unicodeScalars.allSatisfy({ stationAllowed.contains($0) }),
            !name.hasPrefix("/"), name.utf8.count <= 399, !name.contains(".."),
            name.unicodeScalars.allSatisfy({
                CharacterSet(charactersIn: "0123456789abcdefghijklmnopqrstuvwxyz._=/-").contains($0)
            }), name.hasSuffix(".parquet")
        else { throw Failure.unsafePath }
        let components = name.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard !components.isEmpty, components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else {
            throw Failure.unsafePath
        }
        var result = root.appendingPathComponent(station, isDirectory: true)
        for component in components { result.appendPathComponent(component) }
        // Reject symlinks in every existing ancestor, including the destination itself.
        var ancestor = result
        while ancestor.path != root.path {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: ancestor.path),
                attrs[.type] as? FileAttributeType == .typeSymbolicLink
            {
                throw Failure.unsafePath
            }
            ancestor.deleteLastPathComponent()
        }
        return result
    }

    /// The firmware contract makes finalized names immutable. A local receipt avoids
    /// opening every previously collected file on the sensor on each resumed run.
    func completed(station: String, name: String, size: UInt64) throws -> Bool {
        let final = try target(station: station, name: name)
        let receipt = final.appendingPathExtension("verified.json")
        guard FileManager.default.fileExists(atPath: final.path),
            let attributes = try? FileManager.default.attributesOfItem(atPath: receipt.path)
        else { return false }
        guard attributes[.type] as? FileAttributeType == .typeRegular else { throw Failure.unsafePath }
        guard ((attributes[.size] as? NSNumber)?.intValue ?? 0) <= 1024,
            let saved = try? JSONDecoder().decode(Receipt.self, from: Data(contentsOf: receipt))
        else { return false }
        guard saved.size == size, try verified(final, size: size, crc: saved.crc) else {
            throw Failure.conflictingArchive
        }
        return true
    }

    private func record(_ final: URL, size: UInt64, crc: UInt32) throws {
        let receipt = final.appendingPathExtension("verified.json")
        if let attributes = try? FileManager.default.attributesOfItem(atPath: receipt.path),
            attributes[.type] as? FileAttributeType != .typeRegular
        { throw Failure.unsafePath }
        try JSONEncoder().encode(Receipt(size: size, crc: crc)).write(to: receipt, options: .atomic)
    }

    /// Export a snapshot containing finalized Parquet files only, with the Hive tree intact.
    func export(to destination: URL) throws -> Int {
        // Relative enumeration avoids Foundation's /var versus /private/var URL aliases.
        guard let files = FileManager.default.enumerator(atPath: root.path)
        else { throw Failure.invalidFile }
        var count = 0
        for case let relative as String in files {
            try Task.checkCancellation()
            let source = root.appendingPathComponent(relative)
            let values = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw Failure.unsafePath }
            guard values.isRegularFile == true, source.pathExtension == "parquet" else { continue }
            let components = relative.split(separator: "/", maxSplits: 1).map(String.init)
            guard components.count == 2,
                try target(station: components[0], name: components[1]).path == source.path
            else { throw Failure.unsafePath }
            let copy = destination.appendingPathComponent(relative)
            try FileManager.default.createDirectory(
                at: copy.deletingLastPathComponent(), withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: source, to: copy)
            count += 1
        }
        return count
    }

    func prepare(station: String, name: String, size: UInt64, crc: UInt32) throws -> Download? {
        let final = try target(station: station, name: name)
        if FileManager.default.fileExists(atPath: final.path) {
            guard try verified(final, size: size, crc: crc) else { throw Failure.conflictingArchive }
            try record(final, size: size, crc: crc)
            return nil
        }
        try FileManager.default.createDirectory(
            at: final.deletingLastPathComponent(), withIntermediateDirectories: true)
        let partial = final.appendingPathExtension("\(size)-\(String(crc, radix: 16)).partial")
        if FileManager.default.fileExists(atPath: partial.path) {
            let attrs = try FileManager.default.attributesOfItem(atPath: partial.path)
            guard attrs[.type] as? FileAttributeType == .typeRegular else { throw Failure.unsafePath }
            if (attrs[.size] as? NSNumber)?.uint64Value ?? 0 > size { try FileManager.default.removeItem(at: partial) }
        }
        if !FileManager.default.fileExists(atPath: partial.path) {
            guard FileManager.default.createFile(atPath: partial.path, contents: nil) else { throw Failure.invalidFile }
        }
        return try Download(archive: self, final: final, partial: partial, size: size, crc: crc)
    }

    func verified(_ url: URL, size: UInt64, crc: UInt32) throws -> Bool {
        guard size >= 12 else { return false }
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        guard try input.seekToEnd() == size else { return false }
        try input.seek(toOffset: 0)
        let magic = Data("PAR1".utf8)
        guard try input.read(upToCount: 4) == magic else { return false }
        try input.seek(toOffset: size - 4)
        guard try input.read(upToCount: 4) == magic else { return false }
        try input.seek(toOffset: 0)
        var accumulator = UInt32.max
        while let bytes = try input.read(upToCount: 65536), !bytes.isEmpty {
            for byte in bytes {
                accumulator = (accumulator >> 8) ^ Self.crcTable[Int((accumulator ^ UInt32(byte)) & 255)]
            }
        }
        return accumulator ^ UInt32.max == crc
    }

    final class Download {
        let archive: SensorArchive
        let final: URL
        let partial: URL
        let size: UInt64
        let crc: UInt32
        private let output: FileHandle
        private(set) var offset: UInt64
        init(archive: SensorArchive, final: URL, partial: URL, size: UInt64, crc: UInt32) throws {
            self.archive = archive
            self.final = final
            self.partial = partial
            self.size = size
            self.crc = crc
            output = try FileHandle(forUpdating: partial)
            offset = try output.seekToEnd()
        }
        deinit { try? output.close() }
        func append(_ bytes: Data, at receivedOffset: UInt64, windowEnd: UInt64) throws {
            guard receivedOffset == offset, !bytes.isEmpty, windowEnd <= size,
                UInt64(bytes.count) <= windowEnd - min(offset, windowEnd), offset < windowEnd
            else { throw Failure.invalidChunk }
            try output.write(contentsOf: bytes)
            offset += UInt64(bytes.count)
        }
        func persist() throws { try output.synchronize() }
        func commit() throws {
            try persist()
            guard try archive.verified(partial, size: size, crc: crc) else {
                try? FileManager.default.removeItem(at: partial)
                throw Failure.invalidFile
            }
            // moveItem never replaces another finalized archive.
            if FileManager.default.fileExists(atPath: final.path) {
                guard try archive.verified(final, size: size, crc: crc) else { throw Failure.conflictingArchive }
                try FileManager.default.removeItem(at: partial)
            } else {
                try FileManager.default.moveItem(at: partial, to: final)
            }
            try archive.record(final, size: size, crc: crc)
        }
    }
}

/// File reads, writes and CRC verification execute on this actor, away from the UI actor.
actor ArchiveIO {
    private let archive: SensorArchive
    private var download: SensorArchive.Download?
    init(root: URL) throws { archive = try SensorArchive(root: root) }
    func validate(station: String, name: String) throws { _ = try archive.target(station: station, name: name) }
    func completed(station: String, name: String, size: UInt64) throws -> Bool {
        try archive.completed(station: station, name: name, size: size)
    }
    func export(to destination: URL) throws -> Int { try archive.export(to: destination) }
    func prepare(station: String, name: String, size: UInt64, crc: UInt32) throws -> Bool {
        download = try archive.prepare(station: station, name: name, size: size, crc: crc)
        return download != nil
    }
    func offset() -> UInt64 { download?.offset ?? 0 }
    func append(_ data: Data, at offset: UInt64, end: UInt64) throws {
        try download?.append(data, at: offset, windowEnd: end)
    }
    func persist() throws { try download?.persist() }
    func commit() throws {
        try download?.commit()
        download = nil
    }
}
