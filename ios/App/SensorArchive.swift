import Foundation

/// Immutable local copies. Only verified bytes acquire their final .parquet name.
final class SensorArchive {
    enum Failure: Error, LocalizedError {
        case unsafePath, invalidFile, conflictingArchive, invalidChunk
        var errorDescription: String? {
            switch self {
            case .unsafePath: return "The sensor supplied an unsafe archive path."
            case .invalidFile: return "File verification failed (size, CRC-32 or PAR1). Retry sync."
            case .conflictingArchive: return "An existing archive differs. It was preserved."
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
    /// The firmware contract caps archive names at 399 UTF-8 bytes (ble-sync-protocol.md, OPENED).
    static let maxNameBytes = 399

    /// Application Support/SensorArchive, the phone's local archive root.
    static func defaultRoot() throws -> URL {
        try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ).appendingPathComponent("SensorArchive", isDirectory: true)
    }

    /// Readable after the first unlock, matching the Keychain's AfterFirstUnlockThisDeviceOnly token,
    /// so a collection that continues while the phone locks can still write. New files inherit it.
    static var protection: [FileAttributeKey: Any] {
        #if os(iOS)
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        #else
            [:]
        #endif
    }

    init(root: URL) throws {
        self.root = root.standardizedFileURL
        try Self.prepareRoot(self.root)
    }

    /// Creates the root with its protection class and backup exclusion. Later runs only read the
    /// attributes and repair them when an older build created the directory.
    private static func prepareRoot(_ root: URL) throws {
        let manager = FileManager.default
        if !manager.fileExists(atPath: root.path) {
            try manager.createDirectory(at: root, withIntermediateDirectories: true, attributes: protection)
        }
        var directory = root
        if try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup != true {
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
        }
        #if os(iOS)
            let current = try manager.attributesOfItem(atPath: root.path)[.protectionKey] as? FileProtectionType
            if current != .completeUntilFirstUserAuthentication {
                try manager.setAttributes(protection, ofItemAtPath: root.path)
            }
        #endif
    }

    private static let stationAllowed = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")

    /// `[0-9A-Za-z._-]+`, as Android (FileArchive.kt:131) and SyncWire. The station is one directory
    /// name, so no slash can pass, and `.` or `..` alone is refused.
    static func isStationId(_ station: String) -> Bool {
        !station.isEmpty && station != "." && station != ".."
            && station.unicodeScalars.allSatisfy({ stationAllowed.contains($0) })
    }

    func target(station: String, name: String) throws -> URL {
        guard Self.isStationId(station), !name.hasPrefix("/"), name.utf8.count <= Self.maxNameBytes, !name.contains(".."),
            name.unicodeScalars.allSatisfy({
                CharacterSet(charactersIn: "0123456789abcdefghijklmnopqrstuvwxyz._=/-").contains($0)
            }), name.hasSuffix(".parquet"), name.utf8.count > 8
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
        #if os(iOS)
            let options: Data.WritingOptions = [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        #else
            let options: Data.WritingOptions = .atomic
        #endif
        try JSONEncoder().encode(Receipt(size: size, crc: crc)).write(to: receipt, options: options)
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
            at: final.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: Self.protection)
        let partial = final.appendingPathExtension("\(size)-\(String(crc, radix: 16)).partial")
        try removeStalePartials(of: final, keeping: partial)
        if FileManager.default.fileExists(atPath: partial.path) {
            let attrs = try FileManager.default.attributesOfItem(atPath: partial.path)
            guard attrs[.type] as? FileAttributeType == .typeRegular else { throw Failure.unsafePath }
            if (attrs[.size] as? NSNumber)?.uint64Value ?? 0 > size { try FileManager.default.removeItem(at: partial) }
        }
        if !FileManager.default.fileExists(atPath: partial.path) {
            guard FileManager.default.createFile(atPath: partial.path, contents: nil, attributes: Self.protection)
            else { throw Failure.invalidFile }
        }
        return try Download(archive: self, final: final, partial: partial, size: size, crc: crc)
    }

    /// The partial name carries OPENED's size and CRC. When those change for the same name, older
    /// partials can never complete, so they are removed (Android resets on meta mismatch,
    /// FileArchive.kt:97-102).
    private func removeStalePartials(of final: URL, keeping current: URL) throws {
        let directory = final.deletingLastPathComponent()
        let prefix = final.lastPathComponent + "."
        let manager = FileManager.default
        for entry in try manager.contentsOfDirectory(atPath: directory.path)
        where entry.hasPrefix(prefix) && entry.hasSuffix(".partial") && entry != current.lastPathComponent {
            // Only `<size>-<crc hex>.partial`, so a longer final name sharing the prefix is never touched.
            let tag = entry.dropFirst(prefix.count).dropLast(".partial".count)
            let parts = tag.split(separator: "-", omittingEmptySubsequences: false)
            guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty,
                parts[0].allSatisfy({ $0.isASCII && $0.isNumber }), parts[1].allSatisfy({ $0.isASCII && $0.isHexDigit })
            else { continue }
            let stale = directory.appendingPathComponent(entry)
            guard (try? manager.attributesOfItem(atPath: stale.path))?[.type] as? FileAttributeType == .typeRegular
            else { continue }
            try manager.removeItem(at: stale)
        }
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
        /// One READ window: append and make it durable in a single archive call.
        func write(_ bytes: Data, at receivedOffset: UInt64, windowEnd: UInt64) throws {
            try append(bytes, at: receivedOffset, windowEnd: windowEnd)
            try persist()
        }
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
    /// Called once per READ window by the transfer engine.
    func write(_ data: Data, at offset: UInt64, end: UInt64) throws {
        try download?.write(data, at: offset, windowEnd: end)
    }
    func persist() throws { try download?.persist() }
    func commit() throws {
        try download?.commit()
        download = nil
    }
}
