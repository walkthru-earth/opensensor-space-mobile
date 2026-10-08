import Foundation
import Network
import dnssd

/// precondition takes a synchronous autoclosure. This evaluates awaited or throwing checks first.
func check(_ condition: Bool, file: StaticString = #file, line: UInt = #line) {
    precondition(condition, file: file, line: line)
}

/// Deterministic clock shared by a scripted pipe and the engine under test.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: TimeInterval = 0
    var now: TimeInterval { lock.withLock { current } }
    func advance(_ seconds: TimeInterval) { lock.withLock { current += seconds } }
}

actor ScriptedPipe: ArchivePipe {
    private var replies: [ArchiveReply]
    private(set) var opens: [String] = []
    private(set) var reads: [(UInt64, UInt64)] = []
    private(set) var closes: [Int32] = []
    private let clock: TestClock?
    init(_ replies: [ArchiveReply], clock: TestClock? = nil) {
        self.replies = replies
        self.clock = clock
    }
    func list() async throws {}
    func open(name: String) async throws { opens.append(name) }
    func read(handle: Int32, offset: UInt64, length: UInt64) async throws { reads.append((offset, length)) }
    func closeFile(handle: Int32) async throws { closes.append(handle) }
    func receive() async throws -> ArchiveReply {
        clock?.advance(0.1)
        guard !replies.isEmpty else { throw SensorTransfer.Failure.invalidReply }
        return replies.removeFirst()
    }
    var remaining: Int { replies.count }
}

/// Collects progress updates and retry pauses from the engine, in order.
actor Recorder {
    private(set) var updates: [TransferUpdate] = []
    private(set) var pauses: [TimeInterval] = []
    func add(_ update: TransferUpdate) { updates.append(update) }
    func pause(_ seconds: TimeInterval) { pauses.append(seconds) }
}

enum TransferChecks {
    static let bytes = Data("PAR1payloadPAR1".utf8)
    static let crc: Int64 = 0xc907_1203

    static func collect(
        _ pipe: ScriptedPipe, _ archive: ArchiveIO, station: String, maxRead: UInt64 = 16384,
        clock: TestClock? = nil, recorder: Recorder = Recorder()
    ) async throws {
        try await SensorTransfer.collect(
            pipe: pipe, archive: archive, station: station, maxRead: maxRead, now: { clock?.now ?? 0 },
            pause: { await recorder.pause($0) }, progress: { await recorder.add($0) })
    }

    static func run() async throws {
        stateChecks()
        try frameBufferChecks()
        policyChecks()
        try await transferChecks()
        try await windowRetryChecks()
        print(
            "iOS transfer: window retry (short, gap, overlap, duplicate, exhaustion, status), framing, LAN policy, "
                + "reconstructed resume, completed-file skip, rate/throttle, handles and concurrent stations passed")
    }

    private static func stateChecks() {
        var claims = SensorClaims()
        precondition(claims.connect(name: "first", device: "board"))
        claims.closeSession(name: "first")
        precondition(!claims.connect(name: "alias", device: "board"))
        claims.finishTask(name: "first")
        precondition(claims.connect(name: "alias", device: "board"))
        claims.finishTask(name: "alias")
        precondition(!claims.connect(name: "another-alias", device: "board"))
        claims.startTask(name: "alias")
        claims.closeSession(name: "alias")
        precondition(!claims.connect(name: "another-alias", device: "board"))
        claims.finishTask(name: "alias")
        precondition(claims.connect(name: "another-alias", device: "board"))
        var liveness = LanLiveness(lastSent: 0)
        precondition(!liveness.needsKeepalive(now: 119, collecting: false))
        // Frequent incoming LIVE pushes cannot reset the outgoing-traffic deadline.
        for now in stride(from: 10.0, through: 110.0, by: 10.0) {
            precondition(!liveness.needsKeepalive(now: now, collecting: false))
        }
        precondition(liveness.needsKeepalive(now: 120, collecting: false))
        precondition(!liveness.needsKeepalive(now: 120, collecting: true))
        liveness.sent(now: 120)
        precondition(!liveness.needsKeepalive(now: 121, collecting: false))
        precondition(liveness.needsKeepalive(now: 240, collecting: false))
        var updates = TransferProgress(startedAt: 0)
        precondition(updates.record(bytes: 100, now: 0.1) == nil)
        precondition(updates.record(bytes: 100, now: 0.2) == nil)
        precondition(updates.record(bytes: 50, now: 0.25) == 1000)
        precondition(updates.record(bytes: 10, now: 0.3) == nil)
        // File completion must emit immediately even within the throttle interval.
        precondition(updates.record(bytes: 0, now: 0.4, force: true) == 650)
        var empty = TransferProgress(startedAt: 2)
        precondition(empty.record(bytes: 0, now: 2, force: true) == 0)
        precondition(empty.record(bytes: 0, now: 3, force: true) == 0)
    }

    /// LAN framing from an accumulating buffer: split prefixes, split bodies, batches, and the cap.
    private static func frameBufferChecks() throws {
        var frames = LanFrameBuffer()
        frames.append(Data([0x03]))
        check(try frames.next() == nil)
        frames.append(Data([0x00, 0x21, 0x01]))
        check(try frames.next() == nil)
        frames.append(Data([0x02, 0x02, 0x00, 0x07, 0x08, 0x01]))
        check(try frames.next() == Data([0x21, 0x01, 0x02]))
        check(try frames.next() == Data([0x07, 0x08]))
        check(try frames.next() == nil)
        precondition(frames.bufferedBytes == 1)
        frames.append(Data([0x00, 0x09]))
        check(try frames.next() == Data([0x09]))
        precondition(frames.bufferedBytes == 0)
        // A 64 KiB receive can carry many frames. All of them parse without losing order.
        var batch = Data()
        for index in 0..<200 { batch.append(contentsOf: [0x02, 0x00, 0x22, UInt8(index & 0xff)]) }
        frames.append(batch)
        for index in 0..<200 { check(try frames.next() == Data([0x22, UInt8(index & 0xff)])) }
        frames.cap = 4
        frames.append(Data([0x05, 0x00]))
        do {
            _ = try frames.next()
            fatalError("frame above the HELLO payload cap accepted")
        } catch SensorTransfer.Failure.invalidReply {}
        var zero = LanFrameBuffer()
        zero.append(Data([0x00, 0x00]))
        do {
            _ = try zero.next()
            fatalError("empty frame accepted")
        } catch SensorTransfer.Failure.invalidReply {}
    }

    private static func policyChecks() {
        precondition(LanPolicy.isLocalNetworkDenied(.dns(DNSServiceErrorType(kDNSServiceErr_PolicyDenied))))
        precondition(!LanPolicy.isLocalNetworkDenied(.dns(DNSServiceErrorType(kDNSServiceErr_NoSuchRecord))))
        precondition(!LanPolicy.isLocalNetworkDenied(.posix(.ENETDOWN)))
        let parameters = LanPolicy.connectionParameters()
        precondition(parameters.prohibitedInterfaceTypes == [.cellular])
        let tcp = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options
        precondition(tcp?.connectionTimeout == LanPolicy.connectTimeoutSeconds && tcp?.noDelay == true)
    }

    private static func script(handle: Int32 = 7, end: Int64 = 15) -> [ArchiveReply] {
        [
            ArchiveReply(kind: "file", name: "data.parquet", size: 15),
            ArchiveReply(kind: "listEnd", count: 1),
            ArchiveReply(kind: "opened", name: "data.parquet", size: 15, crc: crc, handle: 7),
            ArchiveReply(kind: "chunk", handle: handle, offset: 5, data: bytes.dropFirst(5)),
            ArchiveReply(kind: "readEnd", handle: 7, offset: end),
            ArchiveReply(kind: "closed", handle: 7),
        ]
    }

    private static func transferChecks() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let archive = try ArchiveIO(root: root)
        _ = try await archive.prepare(station: "a", name: "data.parquet", size: 15, crc: UInt32(crc))
        try await archive.append(bytes.prefix(5), at: 0, end: 15)
        try await archive.persist()
        let clock = TestClock()
        let pipe = ScriptedPipe(script(), clock: clock)
        let recorder = Recorder()
        try await collect(pipe, archive, station: "a", clock: clock, recorder: recorder)
        let progressEvents = await recorder.updates
        // Five resumed bytes cannot inflate the rate; only the ten newly received bytes count.
        precondition(progressEvents.last?.filesDone == 1 && progressEvents.last?.bytes == 0)
        precondition(abs(progressEvents.last!.rate - 10 / clock.now) < 0.000001)
        precondition(progressEvents.contains { $0.bytes == 15 })
        let reads = await pipe.reads
        precondition(reads.count == 1 && reads[0].0 == 5 && reads[0].1 == 10)
        check(await pipe.closes == [7])
        let copied = try Data(contentsOf: root.appendingPathComponent("a/data.parquet"))
        precondition(copied == bytes)

        // Reusing the complete archive transfers no bytes and reports no artificial speed.
        let reusedPipe = ScriptedPipe([
            ArchiveReply(kind: "file", name: "data.parquet", size: 15),
            ArchiveReply(kind: "listEnd", count: 1),
        ])
        let reused = Recorder()
        try await collect(reusedPipe, archive, station: "a", recorder: reused)
        check(await reusedPipe.opens.isEmpty)
        check(await reusedPipe.reads.isEmpty)
        check(await reused.updates.last?.rate == 0)

        // The contract's 399-byte name limit applies to LIST entries before any path is built.
        let longName = String(repeating: "a", count: SensorArchive.maxNameBytes - 7) + ".parquet"
        do {
            try await collect(
                ScriptedPipe([ArchiveReply(kind: "file", name: longName, size: 15)]), archive, station: "a")
            fatalError("400-byte name accepted")
        } catch SensorTransfer.Failure.invalidReply {}

        // Lose the connection during B's first window after fully completing A.
        // Reconstruct the archive so resume cannot depend on retained actor state.
        let interruptedPipe = ScriptedPipe([
            ArchiveReply(kind: "file", name: "first.parquet", size: 15),
            ArchiveReply(kind: "file", name: "second.parquet", size: 15),
            ArchiveReply(kind: "listEnd", count: 2),
            ArchiveReply(kind: "opened", name: "first.parquet", size: 15, crc: crc, handle: 7),
            ArchiveReply(kind: "chunk", handle: 7, offset: 0, data: bytes.prefix(8)),
            ArchiveReply(kind: "readEnd", handle: 7, offset: 8),
            ArchiveReply(kind: "chunk", handle: 7, offset: 8, data: bytes.dropFirst(8)),
            ArchiveReply(kind: "readEnd", handle: 7, offset: 15),
            ArchiveReply(kind: "closed", handle: 7),
            ArchiveReply(kind: "opened", name: "second.parquet", size: 15, crc: crc, handle: 8),
            ArchiveReply(kind: "chunk", handle: 8, offset: 0, data: bytes.prefix(5)),
            // The next receive fails before READ_END arrives.
        ])
        do {
            try await collect(interruptedPipe, try ArchiveIO(root: root), station: "restart", maxRead: 8)
            fatalError("interrupted collection unexpectedly completed")
        } catch SensorTransfer.Failure.invalidReply {}
        check(await interruptedPipe.opens == ["first.parquet", "second.parquet"])
        check(await interruptedPipe.closes == [7])
        let firstFinal = root.appendingPathComponent("restart/first.parquet")
        let secondFinal = root.appendingPathComponent("restart/second.parquet")
        let firstBeforeResume = try Data(contentsOf: firstFinal)
        precondition(firstBeforeResume == bytes)
        precondition(!FileManager.default.fileExists(atPath: secondFinal.path))

        let resumeClock = TestClock()
        let resumedPipe = ScriptedPipe(
            [
                ArchiveReply(kind: "file", name: "first.parquet", size: 15),
                ArchiveReply(kind: "file", name: "second.parquet", size: 15),
                ArchiveReply(kind: "listEnd", count: 2),
                ArchiveReply(kind: "opened", name: "second.parquet", size: 15, crc: crc, handle: 9),
                ArchiveReply(kind: "chunk", handle: 9, offset: 5, data: bytes.dropFirst(5).prefix(8)),
                ArchiveReply(kind: "readEnd", handle: 9, offset: 13),
                ArchiveReply(kind: "chunk", handle: 9, offset: 13, data: bytes.dropFirst(13)),
                ArchiveReply(kind: "readEnd", handle: 9, offset: 15),
                ArchiveReply(kind: "closed", handle: 9),
            ], clock: resumeClock)
        let resumed = Recorder()
        try await collect(
            resumedPipe, try ArchiveIO(root: root), station: "restart", maxRead: 8, clock: resumeClock, recorder: resumed)
        check(await resumedPipe.opens == ["second.parquet"])
        let resumedReads = await resumedPipe.reads
        precondition(resumedReads.count == 2)
        precondition(resumedReads[0].0 == 5 && resumedReads[0].1 == 8)
        precondition(resumedReads[1].0 == 13 && resumedReads[1].1 == 2)
        check(await resumedPipe.closes == [9])
        let resumedProgress = await resumed.updates
        precondition(resumedProgress.first == TransferUpdate(filesDone: 1, filesTotal: 2, bytes: 0, rate: 0))
        precondition(resumedProgress.last?.filesDone == 2 && resumedProgress.last?.bytes == 0)
        precondition(abs(resumedProgress.last!.rate - 10 / resumeClock.now) < 0.000001)
        let firstAfterResume = try Data(contentsOf: firstFinal)
        let secondAfterResume = try Data(contentsOf: secondFinal)
        precondition(firstAfterResume == firstBeforeResume && secondAfterResume == bytes)

        // A different session's handle must never write the downloaded payload.
        let bad = try ArchiveIO(root: root)
        _ = try await bad.prepare(station: "b", name: "data.parquet", size: 15, crc: UInt32(crc))
        try await bad.append(bytes.prefix(5), at: 0, end: 15)
        do {
            try await collect(ScriptedPipe(script(handle: 8)), bad, station: "b")
            fatalError("foreign handle accepted")
        } catch SensorTransfer.Failure.invalidReply {}
        check(await bad.offset() == 5)
        precondition(!FileManager.default.fileExists(atPath: root.appendingPathComponent("b/data.parquet").path))

        // A chunk past the requested window is malformed, not lossy. Nothing is written.
        var overrun = script()
        overrun[3] = ArchiveReply(kind: "chunk", handle: 7, offset: 6, data: bytes.dropFirst(5))
        do {
            try await collect(ScriptedPipe(overrun), bad, station: "b")
            fatalError("chunk beyond the READ length accepted")
        } catch SensorTransfer.Failure.invalidReply {}
        check(await bad.offset() == 5)

        // Both stations remain independent when a phone collects concurrently.
        let first = try ArchiveIO(root: root)
        let second = try ArchiveIO(root: root)
        func wholeScript() -> [ArchiveReply] {
            var frames = script()
            frames[3] = ArchiveReply(kind: "chunk", handle: 7, offset: 0, data: bytes)
            return frames
        }
        async let a: Void = collect(ScriptedPipe(wholeScript()), first, station: "c")
        async let b: Void = collect(ScriptedPipe(wholeScript()), second, station: "d")
        _ = try await (a, b)
        precondition(FileManager.default.fileExists(atPath: root.appendingPathComponent("c/data.parquet").path))
        precondition(FileManager.default.fileExists(atPath: root.appendingPathComponent("d/data.parquet").path))
    }

    /// Contract: a failed window is retried with a new READ from the phone's own offset,
    /// overlapping bytes already held are skipped, and next_offset never overrides the local count.
    private static func windowRetryChecks() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let head = [
            ArchiveReply(kind: "file", name: "data.parquet", size: 15),
            ArchiveReply(kind: "listEnd", count: 1),
            ArchiveReply(kind: "opened", name: "data.parquet", size: 15, crc: crc, handle: 7),
        ]
        func chunk(_ from: Int, _ to: Int) -> ArchiveReply {
            ArchiveReply(kind: "chunk", handle: 7, offset: Int64(from), data: bytes.subdata(in: from..<to))
        }
        func end(_ next: Int64, code: Int32 = 0) -> ArchiveReply {
            ArchiveReply(kind: "readEnd", handle: 7, offset: next, code: code)
        }
        let closed = ArchiveReply(kind: "closed", handle: 7)
        func assertComplete(_ station: String) throws {
            let final = root.appendingPathComponent("\(station)/data.parquet")
            let copy = try Data(contentsOf: final)
            precondition(copy == bytes)
            // The engine verified size, CRC-32 and PAR1 before the final name appeared.
            check(try SensorArchive(root: root).verified(final, size: 15, crc: UInt32(crc)))
        }

        // Short window: READ_END claims 15 but only 5 bytes arrived. Retry from 5, not 15.
        let short = ScriptedPipe(head + [chunk(0, 5), end(15), chunk(5, 15), end(15), closed])
        let shortLog = Recorder()
        try await collect(short, try ArchiveIO(root: root), station: "short", recorder: shortLog)
        let shortReads = await short.reads
        precondition(shortReads.count == 2 && shortReads[0] == (0, 15) && shortReads[1] == (5, 10))
        check(await shortLog.pauses == [SensorTransfer.windowRetryBackoff])
        check(await short.closes == [7])
        check(await short.remaining == 0)
        try assertComplete("short")

        // Gap: a lost chunk mid-window. Later chunks are drained until READ_END, then the window
        // is retried from the bytes held. The retry overlaps held bytes and repeats a duplicate.
        let gap = ScriptedPipe(
            head + [
                chunk(0, 4), chunk(8, 12), chunk(12, 15), end(15),
                chunk(2, 6), chunk(4, 6), chunk(6, 15), end(15), closed,
            ])
        let gapLog = Recorder()
        try await collect(gap, try ArchiveIO(root: root), station: "gap", recorder: gapLog)
        let gapReads = await gap.reads
        precondition(gapReads.count == 2 && gapReads[0] == (0, 15) && gapReads[1] == (4, 11))
        check(await gapLog.pauses == [SensorTransfer.windowRetryBackoff])
        try assertComplete("gap")

        // A READ_END that understates the bytes received fails the window, but the phone keeps
        // its own count. All 15 bytes are held, so the file completes without another READ.
        let understated = ScriptedPipe(head + [chunk(0, 15), end(14), closed])
        let understatedLog = Recorder()
        try await collect(understated, try ArchiveIO(root: root), station: "understated", recorder: understatedLog)
        check(await understated.reads.count == 1)
        check(await understatedLog.pauses.count == 1)
        try assertComplete("understated")

        // A consistent short READ_END (the device clipped the window) continues without a retry.
        let clipped = ScriptedPipe(head + [chunk(0, 8), end(8), chunk(8, 15), end(15), closed])
        let clippedLog = Recorder()
        try await collect(clipped, try ArchiveIO(root: root), station: "clipped", recorder: clippedLog)
        let clippedReads = await clipped.reads
        precondition(clippedReads.count == 2 && clippedReads[1] == (8, 7))
        check(await clippedLog.pauses.isEmpty)
        try assertComplete("clipped")

        // Retries are bounded with linear backoff. Held bytes survive for a later resume.
        var failing = head + [chunk(0, 3), end(15)]
        for _ in 0..<SensorTransfer.windowRetries { failing += [end(15)] }
        let exhausted = ScriptedPipe(failing)
        let exhaustedLog = Recorder()
        let exhaustedArchive = try ArchiveIO(root: root)
        do {
            try await collect(exhausted, exhaustedArchive, station: "exhausted", recorder: exhaustedLog)
            fatalError("endless window failure accepted")
        } catch SensorTransfer.Failure.windowLost {}
        let backoff = SensorTransfer.windowRetryBackoff
        check(await exhaustedLog.pauses == (1...SensorTransfer.windowRetries).map { backoff * Double($0) })
        let exhaustedReads = await exhausted.reads
        precondition(exhaustedReads.count == SensorTransfer.windowRetries + 1)
        precondition(exhaustedReads.dropFirst().allSatisfy { $0 == (3, 12) })
        check(await exhaustedArchive.offset() == 3)
        precondition(!FileManager.default.fileExists(atPath: root.appendingPathComponent("exhausted/data.parquet").path))

        // A device read error is reported, not retried, and keeps the bytes held so far.
        let status = ScriptedPipe(head + [chunk(0, 6), end(6, code: 7)])
        let statusArchive = try ArchiveIO(root: root)
        do {
            try await collect(status, statusArchive, station: "status")
            fatalError("READ_END error status accepted")
        } catch SensorTransfer.Failure.readFailed(7) {}
        check(await statusArchive.offset() == 6)

        // Resume after exhaustion starts from the retained offset and completes with the right CRC.
        let resumed = ScriptedPipe(head + [chunk(3, 15), end(15), closed])
        try await collect(resumed, try ArchiveIO(root: root), station: "exhausted")
        let resumedReads = await resumed.reads
        precondition(resumedReads.count == 1 && resumedReads[0] == (3, 12))
        check(await resumed.closes == [7])
        let resumedFinal = root.appendingPathComponent("exhausted/data.parquet")
        check(try SensorArchive(root: root).verified(resumedFinal, size: 15, crc: UInt32(crc)))
    }
}
