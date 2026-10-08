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
    /// ERROR only: the opcode of the request it answers.
    var op: Int32 = 0
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

/// A listed file that this run did not save. The run went on with the next file.
struct FileFailure: Sendable, Equatable {
    let name: String
    let reason: SensorTransfer.Failure
}

/// Every listed file was saved, already archived, or reported in `failures`.
struct TransferSummary: Sendable, Equatable {
    let files: Int
    let failures: [FileFailure]
}

/// The collection engine runs off the main actor. Archive writes hop to `ArchiveIO` once per
/// READ window, and progress hops to the caller at most four times per second.
enum SensorTransfer {
    enum Failure: Error, LocalizedError, Equatable, Sendable {
        case invalidReply, windowLost, readFailed(Int32)
        /// Per-file outcomes. Each one fails only its file (Android SyncEngine.kt:116-216).
        case invalidEntry, mismatch, deviceError(Int32), localConflict, verifyFailed
        /// Reported after every other listed file was tried.
        case filesFailed(failed: Int, total: Int)
        var errorDescription: String? {
            switch self {
            case .invalidReply: return "The sensor sent an unexpected file reply. Retry sync to resume."
            case .windowLost:
                return "File data kept arriving incomplete. Check the connection and retry sync. Saved bytes are kept."
            case .readFailed(let code):
                return "The sensor could not read a file (code \(code)). Retry sync later. Saved bytes are kept."
            case .invalidEntry: return "The sensor listed a file with an unsafe name or size. It was skipped."
            case .mismatch: return "The sensor described a file differently when opening it. It was skipped."
            case .deviceError(let code): return "The sensor refused a file request (code \(code)). Retry sync later."
            case .localConflict: return "A file on this phone differs from the sensor's copy. It was preserved."
            case .verifyFailed: return "File verification failed (size, CRC-32 or PAR1). Retry sync."
            case .filesFailed(let failed, let total):
                return "\(failed) of \(total) files were not saved. The others are verified on this phone. Retry sync later."
            }
        }
    }

    /// Mirrors Android's SyncEngine. Six attempts per window in total, with linear backoff
    /// (0.3 s, 0.6 s, ...) so a busy link can catch up without stalling the collection.
    static let windowRetries = 5
    static let windowRetryBackoff: TimeInterval = 0.3

    /// Request opcodes that an ERROR frame echoes (ble-sync-protocol.md, ERROR.op).
    enum Op {
        static let open: Int32 = 0x02
        static let read: Int32 = 0x03
        static let close: Int32 = 0x04
    }

    /// Collects every listed file. One bad file is reported in the summary and the run continues.
    /// Connection and protocol faults throw and end the run.
    @discardableResult
    static func collect(
        pipe: some ArchivePipe, archive: ArchiveIO, station: String, maxRead: UInt64,
        now: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
        pause: @escaping @Sendable (TimeInterval) async throws -> Void = { seconds in
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        },
        progress: @escaping @Sendable (TransferUpdate) async -> Void
    ) async throws -> TransferSummary {
        var run = Run(
            pipe: pipe, archive: archive, station: station, maxRead: maxRead, now: now, pause: pause,
            progress: progress, updates: TransferProgress(startedAt: now()))
        do {
            return try await run.collectFiles()
        } catch {
            // Retain even a partly received READ window before closing the writer.
            try? await archive.persist()
            throw error
        }
    }

    /// An ERROR answering this file's request fails only this file. An ERROR for another request
    /// is a protocol fault (Android DeviceSession.kt:397-400).
    static func refusal(_ frame: ArchiveReply, op: Int32) throws -> Failure? {
        guard frame.kind == "error" else { return nil }
        guard frame.op == op else { throw Failure.invalidReply }
        return .deviceError(frame.code)
    }

    /// One collection run and the progress state shared by its files.
    private struct Run<Pipe: ArchivePipe> {
        let pipe: Pipe
        let archive: ArchiveIO
        let station: String
        let maxRead: UInt64
        let now: @Sendable () -> TimeInterval
        let pause: @Sendable (TimeInterval) async throws -> Void
        let progress: @Sendable (TransferUpdate) async -> Void
        var updates: TransferProgress
        var finished = 0
        var total = 0

        mutating func collectFiles() async throws -> TransferSummary {
            guard (1...16384).contains(maxRead) else { throw Failure.invalidReply }
            try await pipe.list()
            var files: [(String, UInt64)] = []
            var names = Set<String>()
            var failures: [FileFailure] = []
            while true {
                let frame = try await pipe.receive()
                if frame.kind == "listEnd" {
                    guard Int(frame.count) == total else { throw Failure.invalidReply }
                    break
                }
                guard frame.kind == "file", total < 65535 else { throw Failure.invalidReply }
                total += 1
                // One odd entry is reported and the others continue (Android SyncPlanner.kt:96-102).
                guard frame.size >= 12, frame.name.utf8.count <= SensorArchive.maxNameBytes,
                    names.insert(frame.name).inserted,
                    (try? await archive.validate(station: station, name: frame.name)) != nil
                else {
                    failures.append(FileFailure(name: frame.name, reason: .invalidEntry))
                    continue
                }
                files.append((frame.name, UInt64(frame.size)))
            }
            var pending: [(String, UInt64)] = []
            for (name, size) in files {
                try Task.checkCancellation()
                do {
                    if try await archive.completed(station: station, name: name, size: size) { continue }
                } catch SensorArchive.Failure.conflictingArchive, SensorArchive.Failure.unsafePath {
                    // A differing local copy is preserved and reported. It never blocks other files.
                    failures.append(FileFailure(name: name, reason: .localConflict))
                    continue
                }
                pending.append((name, size))
            }
            finished = total - pending.count
            await progress(TransferUpdate(filesDone: finished, filesTotal: total, bytes: 0, rate: 0))
            for (name, size) in pending {
                try Task.checkCancellation()
                if let reason = try await download(name: name, size: size) {
                    failures.append(FileFailure(name: name, reason: reason))
                }
                finished += 1
                if let rate = updates.record(bytes: 0, now: now(), force: true) {
                    await progress(TransferUpdate(filesDone: finished, filesTotal: total, bytes: 0, rate: rate))
                }
            }
            return TransferSummary(files: total, failures: failures)
        }

        /// OPEN, transfer and CLOSE one file. Returns why the file was not saved, nil when it was.
        private mutating func download(name: String, size: UInt64) async throws -> Failure? {
            try await pipe.open(name: name)
            let opened = try await pipe.receive()
            if let refused = try SensorTransfer.refusal(opened, op: Op.open) { return refused }
            guard opened.kind == "opened", (1...65535).contains(opened.handle) else { throw Failure.invalidReply }
            let failure: Failure?
            // Finalized files are immutable, so OPENED must describe what LIST did (Android SyncEngine.kt:116-124).
            if opened.name != name || opened.size != Int64(size) || !(0...Int64(UInt32.max)).contains(opened.crc) {
                failure = .mismatch
            } else {
                failure = try await transfer(
                    name: name, handle: opened.handle, size: size, crc: UInt32(opened.crc))
            }
            try await closeFile(handle: opened.handle)
            return failure
        }

        /// CLOSE never fails a file (Android SyncEngine.kt:262-266). Any other reply is a protocol fault.
        private func closeFile(handle: Int32) async throws {
            try await pipe.closeFile(handle: handle)
            let closed = try await pipe.receive()
            if try SensorTransfer.refusal(closed, op: Op.close) != nil { return }
            guard closed.kind == "closed", closed.handle == handle else { throw Failure.invalidReply }
        }

        /// Reads into the partial, then verifies and commits. A corrupt resumed prefix is discarded
        /// and the file is read once more from zero with the same handle (Android SyncEngine.kt:231-243).
        private mutating func transfer(name: String, handle: Int32, size: UInt64, crc: UInt32) async throws
            -> Failure?
        {
            var restarted = false
            while true {
                do {
                    guard try await archive.prepare(station: station, name: name, size: size, crc: crc) else {
                        return nil
                    }
                } catch SensorArchive.Failure.conflictingArchive, SensorArchive.Failure.unsafePath {
                    return .localConflict
                }
                let resumedFrom = await archive.offset()
                if let failure = try await readWindows(handle: handle, from: resumedFrom, size: size) {
                    return failure
                }
                do {
                    try await archive.commit()
                    return nil
                } catch SensorArchive.Failure.invalidFile {
                    // The commit removed the partial, so the next prepare starts from zero.
                    if resumedFrom > 0, !restarted {
                        restarted = true
                        continue
                    }
                    return .verifyFailed
                } catch SensorArchive.Failure.conflictingArchive {
                    return .localConflict
                }
            }
        }

        private mutating func readWindows(handle: Int32, from resumedFrom: UInt64, size: UInt64) async throws
            -> Failure?
        {
            var offset = resumedFrom
            await progress(TransferUpdate(filesDone: finished, filesTotal: total, bytes: Int64(offset), rate: 0))
            var failedAttempts = 0
            while offset < size {
                try Task.checkCancellation()
                let start = offset
                let end = start + min(maxRead, size - start)
                try await pipe.read(handle: handle, offset: start, length: end - start)
                // Contiguous bytes from `start`. They are written in one archive hop per window.
                var held = Data()
                var lost = false
                // READ_END, or an ERROR that ends this request.
                let last: ArchiveReply
                do {
                    while true {
                        try Task.checkCancellation()
                        let frame = try await pipe.receive()
                        if frame.kind == "error" {
                            last = frame
                            break
                        }
                        guard frame.handle == handle else { throw Failure.invalidReply }
                        if frame.kind == "readEnd" {
                            last = frame
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
                                    filesDone: finished, filesTotal: total, bytes: Int64(expected) + Int64(fresh.count),
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
                if let refused = try SensorTransfer.refusal(last, op: Op.read) { return refused }
                guard last.code == 0 else { return .readFailed(last.code) }
                // Never trust next_offset over the phone's own count. A disagreement or a gap
                // fails the window, which is retried with a new READ from the phone's offset.
                if lost || last.offset < 0 || UInt64(last.offset) != offset {
                    failedAttempts += 1
                    guard failedAttempts <= windowRetries else { return .windowLost }
                    try await pause(windowRetryBackoff * Double(failedAttempts))
                    continue
                }
                // A consistent READ_END without data before end of file cannot make progress.
                guard offset > start else { return .invalidReply }
                failedAttempts = 0
                if offset == size, let rate = updates.record(bytes: 0, now: now(), force: true) {
                    await progress(
                        TransferUpdate(filesDone: finished, filesTotal: total, bytes: Int64(offset), rate: rate))
                }
            }
            return nil
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
