import Foundation

/// Platform transfer adapter; wire values remain in the shared Kotlin codec.
@MainActor protocol ArchivePipe: AnyObject {
    func list() async throws
    func open(name: String) async throws
    func read(handle: Int32, offset: UInt64, length: UInt64) async throws
    func closeFile(handle: Int32) async throws
    func receive() async throws -> ArchiveReply
}
struct ArchiveReply {
    let kind: String
    var name = ""
    var size: Int64 = 0
    var crc: Int64 = 0
    var handle: Int32 = 0
    var offset: Int64 = 0
    var code: Int32 = 0
    var count: Int32 = 0
    var data: Data? = nil
}
@MainActor enum SensorTransfer {
    enum Failure: Error, LocalizedError {
        case invalidReply
        var errorDescription: String? { "The sensor sent an unexpected file reply. Retry sync to resume." }
    }
    static func collect(
        pipe: ArchivePipe, archive: ArchiveIO, station: String, maxRead: UInt64, progress: (Int, Int, Int64) -> Void
    ) async throws {
        guard (1...16384).contains(maxRead) else { throw Failure.invalidReply }
        try await pipe.list()
        var files: [(String, UInt64)] = []
        var names = Set<String>()
        while true {
            let frame = try await pipe.receive()
            if frame.kind == "listEnd" {
                guard Int(frame.count) == files.count else { throw SensorTransfer.Failure.invalidReply }
                break
            }
            guard frame.kind == "file", files.count < 65535, frame.size >= 12, names.insert(frame.name).inserted,
                frame.name.utf8.count <= 399
            else { throw SensorTransfer.Failure.invalidReply }
            try await archive.validate(station: station, name: frame.name)
            files.append((frame.name, UInt64(frame.size)))
        }
        var finished = 0
        for (name, size) in files {
            try Task.checkCancellation()
            try await pipe.open(name: name)
            let opened = try await pipe.receive()
            guard opened.kind == "opened", opened.name == name, opened.size >= 12, UInt64(opened.size) == size,
                (1...65535).contains(opened.handle), (0...Int64(UInt32.max)).contains(opened.crc)
            else {
                throw SensorTransfer.Failure.invalidReply
            }
            if try await archive.prepare(station: station, name: name, size: size, crc: UInt32(opened.crc)) {
                var offset = await archive.offset()
                while offset < size {
                    let length = min(maxRead, size - offset)
                    let end = offset + length
                    try await pipe.read(handle: opened.handle, offset: offset, length: length)
                    while true {
                        let frame = try await pipe.receive()
                        guard frame.handle == opened.handle else { throw SensorTransfer.Failure.invalidReply }
                        if frame.kind == "readEnd" {
                            guard frame.code == 0, frame.offset >= 0, UInt64(frame.offset) == offset, offset == end
                            else {
                                throw SensorTransfer.Failure.invalidReply
                            }
                            try await archive.persist()
                            break
                        }
                        guard frame.kind == "chunk", let bytes = frame.data, frame.offset >= 0 else {
                            throw SensorTransfer.Failure.invalidReply
                        }
                        try await archive.append(bytes, at: UInt64(frame.offset), end: end)
                        offset = await archive.offset()
                        progress(finished, files.count, Int64(offset))
                    }
                }
                try await archive.commit()
            }
            try await pipe.closeFile(handle: opened.handle)
            let closed = try await pipe.receive()
            guard closed.kind == "closed", closed.handle == opened.handle else {
                throw SensorTransfer.Failure.invalidReply
            }
            finished += 1
            progress(finished, files.count, 0)
        }
    }
}

/// Firmware's idle timer measures phone traffic, independently of unsolicited sensor pushes.
struct LanLiveness {
    private(set) var lastSent: TimeInterval
    mutating func sent(now: TimeInterval) { lastSent = now }
    func needsKeepalive(now: TimeInterval, collecting: Bool) -> Bool {
        !collecting && now - lastSent >= 120
    }
}

/// Keep hardware ownership until both the socket and its in-flight archive work have ended.
struct SensorClaims {
    private var sessions: [String: String] = [:]
    private var tasks: [String: String] = [:]
    mutating func connect(name: String, device: String) -> Bool {
        guard !sessions.values.contains(device), !tasks.values.contains(device) else { return false }
        sessions[name] = device
        tasks[name] = device
        return true
    }
    mutating func startTask(name: String) {
        if let device = sessions[name] { tasks[name] = device }
    }
    mutating func closeSession(name: String) { sessions[name] = nil }
    mutating func finishTask(name: String) { tasks[name] = nil }
}
