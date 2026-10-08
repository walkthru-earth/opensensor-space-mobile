import Foundation

/// Platform transfer adapter; wire values remain in the shared Kotlin codec.
/// Implementations serialize their own state (LanSession is an actor on its socket queue).
protocol ArchivePipe: AnyObject, Sendable {
    func list() async throws
    func open(name: String) async throws
    func read(handle: Int32, offset: UInt64, length: UInt64) async throws
    func closeFile(handle: Int32) async throws
    func receive() async throws -> ArchiveReply
}

/// One decoded reply or push. Plain Swift values, so replies cross isolation domains safely.
struct ArchiveReply: Sendable {
    let kind: String
    var name = ""
    var size: Int64 = 0
    var crc: Int64 = 0
    var handle: Int32 = 0
    var offset: Int64 = 0
    var code: Int32 = 0
    var count: Int32 = 0
    var data: Data? = nil
    var json = ""
}

/// Files done, files listed, bytes held for the current file and the end-to-end rate.
struct TransferUpdate: Sendable, Equatable {
    let filesDone: Int
    let filesTotal: Int
    let bytes: Int64
    let rate: Double
}

/// The collection engine runs off the main actor. Archive writes hop to `ArchiveIO` once per
/// READ window, and progress hops to the caller at most four times per second.
enum SensorTransfer {
    enum Failure: Error, LocalizedError, Equatable {
        case invalidReply, windowLost, readFailed(Int32)
        var errorDescription: String? {
            switch self {
            case .invalidReply: return "The sensor sent an unexpected file reply. Retry sync to resume."
            case .windowLost:
                return "File data kept arriving incomplete. Check the connection and retry sync. Saved bytes are kept."
            case .readFailed(let code):
                return "The sensor could not read a file (code \(code)). Retry sync later. Saved bytes are kept."
            }
        }
    }

    /// Mirrors Android's SyncEngine. Six attempts per window in total, with linear backoff
    /// (0.3 s, 0.6 s, ...) so a busy link can catch up without stalling the collection.
    static let windowRetries = 5
    static let windowRetryBackoff: TimeInterval = 0.3

    static func collect(
        pipe: some ArchivePipe, archive: ArchiveIO, station: String, maxRead: UInt64,
        now: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
        pause: @escaping @Sendable (TimeInterval) async throws -> Void = { seconds in
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        },
        progress: @escaping @Sendable (TransferUpdate) async -> Void
    ) async throws {
        do {
            try await collectFiles(
                pipe: pipe, archive: archive, station: station, maxRead: maxRead, now: now, pause: pause,
                progress: progress)
        } catch {
            // Retain even a partly received READ window before closing the writer.
            try? await archive.persist()
            throw error
        }
    }

    private static func collectFiles(
        pipe: some ArchivePipe, archive: ArchiveIO, station: String, maxRead: UInt64,
        now: @Sendable () -> TimeInterval, pause: @Sendable (TimeInterval) async throws -> Void,
        progress: @Sendable (TransferUpdate) async -> Void
    ) async throws {
        guard (1...16384).contains(maxRead) else { throw Failure.invalidReply }
        var updates = TransferProgress(startedAt: now())
        try await pipe.list()
        var files: [(String, UInt64)] = []
        var names = Set<String>()
        while true {
            let frame = try await pipe.receive()
            if frame.kind == "listEnd" {
                guard Int(frame.count) == files.count else { throw Failure.invalidReply }
                break
            }
            guard frame.kind == "file", files.count < 65535, frame.size >= 12, names.insert(frame.name).inserted,
                frame.name.utf8.count <= SensorArchive.maxNameBytes
            else { throw Failure.invalidReply }
            try await archive.validate(station: station, name: frame.name)
            files.append((frame.name, UInt64(frame.size)))
        }
        var completed = Set<String>()
        for (name, size) in files {
            try Task.checkCancellation()
            if try await archive.completed(station: station, name: name, size: size) { completed.insert(name) }
        }
        var finished = completed.count
        await progress(TransferUpdate(filesDone: finished, filesTotal: files.count, bytes: 0, rate: 0))
        for (name, size) in files {
            try Task.checkCancellation()
            if completed.contains(name) { continue }
            try await pipe.open(name: name)
            let opened = try await pipe.receive()
            guard opened.kind == "opened", opened.name == name, opened.size >= 12, UInt64(opened.size) == size,
                (1...65535).contains(opened.handle), (0...Int64(UInt32.max)).contains(opened.crc)
            else {
                throw Failure.invalidReply
            }
            if try await archive.prepare(station: station, name: name, size: size, crc: UInt32(opened.crc)) {
                var offset = await archive.offset()
                await progress(TransferUpdate(filesDone: finished, filesTotal: files.count, bytes: Int64(offset), rate: 0))
                var failedAttempts = 0
                while offset < size {
                    try Task.checkCancellation()
                    let start = offset
                    let end = start + min(maxRead, size - start)
                    try await pipe.read(handle: opened.handle, offset: start, length: end - start)
                    // Contiguous bytes from `start`. They are written in one archive hop per window.
                    var held = Data()
                    var lost = false
                    let readEnd: ArchiveReply
                    do {
                        while true {
                            try Task.checkCancellation()
                            let frame = try await pipe.receive()
                            guard frame.handle == opened.handle else { throw Failure.invalidReply }
                            if frame.kind == "readEnd" {
                                readEnd = frame
                                break
                            }
                            guard frame.kind == "chunk", let bytes = frame.data, !bytes.isEmpty, frame.offset >= 0,
                                UInt64(frame.offset) + UInt64(bytes.count) <= end
                            else {
                                throw Failure.invalidReply
                            }
                            let chunkStart = UInt64(frame.offset)
                            let expected = start + UInt64(held.count)
                            // After a gap, keep reading to this request's READ_END before retrying.
                            if lost { continue }
                            if chunkStart > expected {
                                lost = true
                                continue
                            }
                            // Duplicate or overlapping bytes are identical (immutable file). Skip what is held.
                            let skip = Int(expected - chunkStart)
                            guard skip < bytes.count else { continue }
                            let fresh = bytes.dropFirst(skip)
                            held.append(fresh)
                            if let rate = updates.record(bytes: fresh.count, now: now()) {
                                await progress(
                                    TransferUpdate(
                                        filesDone: finished, filesTotal: files.count, bytes: Int64(expected) + Int64(fresh.count),
                                        rate: rate))
                            }
                        }
                    } catch {
                        // Bytes before a failure are good (absolute offsets). Keep them for resume.
                        if !held.isEmpty { try? await archive.append(held, at: start, end: end) }
                        throw error
                    }
                    if !held.isEmpty { try await archive.write(held, at: start, end: end) }
                    offset = start + UInt64(held.count)
                    guard readEnd.code == 0 else { throw Failure.readFailed(readEnd.code) }
                    // Never trust next_offset over the phone's own count. A disagreement or a gap
                    // fails the window, which is retried with a new READ from the phone's offset.
                    if lost || readEnd.offset < 0 || UInt64(readEnd.offset) != offset {
                        failedAttempts += 1
                        guard failedAttempts <= windowRetries else { throw Failure.windowLost }
                        try await pause(windowRetryBackoff * Double(failedAttempts))
                        continue
                    }
                    // A consistent READ_END without data before end of file cannot make progress.
                    guard offset > start else { throw Failure.invalidReply }
                    failedAttempts = 0
                    if offset == size, let rate = updates.record(bytes: 0, now: now(), force: true) {
                        await progress(
                            TransferUpdate(filesDone: finished, filesTotal: files.count, bytes: Int64(offset), rate: rate))
                    }
                }
                try await archive.commit()
            }
            try await pipe.closeFile(handle: opened.handle)
            let closed = try await pipe.receive()
            guard closed.kind == "closed", closed.handle == opened.handle else {
                throw Failure.invalidReply
            }
            finished += 1
            if let rate = updates.record(bytes: 0, now: now(), force: true) {
                await progress(TransferUpdate(filesDone: finished, filesTotal: files.count, bytes: 0, rate: rate))
            }
        }
    }
}

/// End-to-end collection rate counts only newly written bytes, never resumed or reused bytes.
/// UI work is limited to four updates per second; final bytes and file completion are immediate.
struct TransferProgress {
    private let startedAt: TimeInterval
    private var reportedAt: TimeInterval
    private var transferredBytes: Int64 = 0
    init(startedAt: TimeInterval) {
        self.startedAt = startedAt
        reportedAt = startedAt
    }
    mutating func record(bytes: Int, now: TimeInterval, force: Bool = false) -> Double? {
        transferredBytes += Int64(bytes)
        guard force || now - reportedAt >= 0.25 else { return nil }
        reportedAt = now
        let elapsed = now - startedAt
        return elapsed > 0 ? Double(transferredBytes) / elapsed : 0
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

/// Accumulates LAN bytes and splits complete `len` u16 LE frames. Bounded by the HELLO payload cap.
struct LanFrameBuffer {
    private var buffer = Data()
    private var head = 0
    var cap = 1024
    var bufferedBytes: Int { buffer.count - head }
    mutating func append(_ data: Data) {
        if head > 0, head == buffer.count {
            buffer.removeAll(keepingCapacity: true)
            head = 0
        }
        buffer.append(data)
    }
    /// Returns the next complete frame body, nil when more bytes are needed.
    mutating func next() throws -> Data? {
        guard bufferedBytes >= 2 else {
            compact()
            return nil
        }
        let base = buffer.startIndex + head
        let count = Int(buffer[base]) | (Int(buffer[base + 1]) << 8)
        guard (1...cap).contains(count) else { throw SensorTransfer.Failure.invalidReply }
        guard bufferedBytes >= 2 + count else {
            compact()
            return nil
        }
        let body = buffer.subdata(in: (base + 2)..<(base + 2 + count))
        head += 2 + count
        return body
    }
    private mutating func compact() {
        guard head > 0 else { return }
        buffer.removeSubrange(buffer.startIndex..<(buffer.startIndex + head))
        head = 0
    }
}
