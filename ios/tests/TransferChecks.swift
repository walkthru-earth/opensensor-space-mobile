import Foundation

@MainActor private final class ScriptedPipe: ArchivePipe {
    var replies: [ArchiveReply]
    var opens: [String] = []
    var reads: [(UInt64, UInt64)] = []
    var closes: [Int32] = []
    var onReceive: (() -> Void)?
    init(_ replies: [ArchiveReply]) { self.replies = replies }
    func list() async throws {}
    func open(name: String) async throws { opens.append(name) }
    func read(handle: Int32, offset: UInt64, length: UInt64) async throws { reads.append((offset, length)) }
    func closeFile(handle: Int32) async throws { closes.append(handle) }
    func receive() async throws -> ArchiveReply {
        onReceive?()
        guard !replies.isEmpty else { throw SensorTransfer.Failure.invalidReply }
        return replies.removeFirst()
    }
}

@MainActor enum TransferChecks {
    static func run() async throws {
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
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data("PAR1payloadPAR1".utf8)
        let crc: Int64 = 0xc907_1203
        func script(handle: Int32 = 7, end: Int64 = 15) -> [ArchiveReply] {
            [
                ArchiveReply(kind: "file", name: "data.parquet", size: 15),
                ArchiveReply(kind: "listEnd", count: 1),
                ArchiveReply(kind: "opened", name: "data.parquet", size: 15, crc: crc, handle: 7),
                ArchiveReply(kind: "chunk", handle: handle, offset: 5, data: bytes.dropFirst(5)),
                ArchiveReply(kind: "readEnd", handle: 7, offset: end),
                ArchiveReply(kind: "closed", handle: 7),
            ]
        }
        let archive = try ArchiveIO(root: root)
        _ = try await archive.prepare(station: "a", name: "data.parquet", size: 15, crc: UInt32(crc))
        try await archive.append(bytes.prefix(5), at: 0, end: 15)
        try await archive.persist()
        let pipe = ScriptedPipe(script())
        var clock: TimeInterval = 0
        pipe.onReceive = { clock += 0.1 }
        var progressEvents: [(Int, Int, Int64, Double)] = []
        try await SensorTransfer.collect(
            pipe: pipe, archive: archive, station: "a", maxRead: 16384, now: { clock }
        ) { progressEvents.append(($0, $1, $2, $3)) }
        // Five resumed bytes cannot inflate the rate; only the ten newly received bytes count.
        precondition(progressEvents.last?.0 == 1 && progressEvents.last?.2 == 0)
        precondition(abs(progressEvents.last!.3 - 10 / clock) < 0.000001)
        precondition(progressEvents.contains { $0.2 == 15 })
        precondition(pipe.reads.count == 1 && pipe.reads[0].0 == 5 && pipe.reads[0].1 == 10)
        precondition(pipe.closes == [7])
        let copied = try Data(contentsOf: root.appendingPathComponent("a/data.parquet"))
        precondition(copied == bytes)

        // Reusing the complete archive transfers no bytes and reports no artificial speed.
        let reusedPipe = ScriptedPipe([
            ArchiveReply(kind: "file", name: "data.parquet", size: 15),
            ArchiveReply(kind: "listEnd", count: 1),
        ])
        var reusedRate = -1.0
        try await SensorTransfer.collect(pipe: reusedPipe, archive: archive, station: "a", maxRead: 16384) {
            _, _, _, rate in reusedRate = rate
        }
        precondition(reusedPipe.opens.isEmpty && reusedPipe.reads.isEmpty && reusedRate == 0)

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
        func interruptedCollection() async throws {
            let interruptedArchive = try ArchiveIO(root: root)
            try await SensorTransfer.collect(
                pipe: interruptedPipe, archive: interruptedArchive, station: "restart", maxRead: 8
            ) { _, _, _, _ in }
        }
        do {
            try await interruptedCollection()
            fatalError("interrupted collection unexpectedly completed")
        } catch SensorTransfer.Failure.invalidReply {}
        precondition(interruptedPipe.opens == ["first.parquet", "second.parquet"])
        precondition(interruptedPipe.closes == [7])
        let firstFinal = root.appendingPathComponent("restart/first.parquet")
        let secondFinal = root.appendingPathComponent("restart/second.parquet")
        let firstBeforeResume = try Data(contentsOf: firstFinal)
        precondition(firstBeforeResume == bytes)
        precondition(!FileManager.default.fileExists(atPath: secondFinal.path))

        let reconstructedArchive = try ArchiveIO(root: root)
        let resumedPipe = ScriptedPipe([
            ArchiveReply(kind: "file", name: "first.parquet", size: 15),
            ArchiveReply(kind: "file", name: "second.parquet", size: 15),
            ArchiveReply(kind: "listEnd", count: 2),
            ArchiveReply(kind: "opened", name: "second.parquet", size: 15, crc: crc, handle: 9),
            ArchiveReply(kind: "chunk", handle: 9, offset: 5, data: bytes.dropFirst(5).prefix(8)),
            ArchiveReply(kind: "readEnd", handle: 9, offset: 13),
            ArchiveReply(kind: "chunk", handle: 9, offset: 13, data: bytes.dropFirst(13)),
            ArchiveReply(kind: "readEnd", handle: 9, offset: 15),
            ArchiveReply(kind: "closed", handle: 9),
        ])
        var resumeClock: TimeInterval = 0
        resumedPipe.onReceive = { resumeClock += 0.1 }
        var resumedProgress: [(Int, Int, Int64, Double)] = []
        try await SensorTransfer.collect(
            pipe: resumedPipe, archive: reconstructedArchive, station: "restart", maxRead: 8,
            now: { resumeClock }
        ) { resumedProgress.append(($0, $1, $2, $3)) }
        precondition(resumedPipe.opens == ["second.parquet"])
        precondition(resumedPipe.reads.count == 2)
        precondition(resumedPipe.reads[0].0 == 5 && resumedPipe.reads[0].1 == 8)
        precondition(resumedPipe.reads[1].0 == 13 && resumedPipe.reads[1].1 == 2)
        precondition(resumedPipe.closes == [9])
        precondition(resumedProgress.first?.0 == 1 && resumedProgress.first?.1 == 2)
        precondition(resumedProgress.first?.2 == 0 && resumedProgress.first?.3 == 0)
        precondition(resumedProgress.last?.0 == 2 && resumedProgress.last?.2 == 0)
        precondition(abs(resumedProgress.last!.3 - 10 / resumeClock) < 0.000001)
        let firstAfterResume = try Data(contentsOf: firstFinal)
        let secondAfterResume = try Data(contentsOf: secondFinal)
        precondition(firstAfterResume == firstBeforeResume && secondAfterResume == bytes)

        // A different session's handle must never write the downloaded payload.
        let bad = try ArchiveIO(root: root)
        _ = try await bad.prepare(station: "b", name: "data.parquet", size: 15, crc: UInt32(crc))
        try await bad.append(bytes.prefix(5), at: 0, end: 15)
        let wrongHandle = ScriptedPipe(script(handle: 8))
        do {
            try await SensorTransfer.collect(pipe: wrongHandle, archive: bad, station: "b", maxRead: 16384) { _, _, _, _ in
            }
            fatalError("foreign handle accepted")
        } catch SensorTransfer.Failure.invalidReply {}
        let retained = await bad.offset()
        precondition(retained == 5)
        precondition(!FileManager.default.fileExists(atPath: root.appendingPathComponent("b/data.parquet").path))

        // A gap/duplicate offset must not advance the local count after eliminating offset actor hops.
        var gapFrames = script()
        gapFrames[3] = ArchiveReply(kind: "chunk", handle: 7, offset: 6, data: bytes.dropFirst(5))
        do {
            try await SensorTransfer.collect(
                pipe: ScriptedPipe(gapFrames), archive: bad, station: "b", maxRead: 16384
            ) { _, _, _, _ in }
            fatalError("noncontiguous chunk accepted")
        } catch SensorTransfer.Failure.invalidReply {}
        let afterGap = await bad.offset()
        precondition(afterGap == 5)

        // READ_END cannot advance the phone's received byte count or finalize a failed window.
        let inconsistent = ScriptedPipe(script(end: 14))
        do {
            try await SensorTransfer.collect(pipe: inconsistent, archive: bad, station: "b", maxRead: 16384) {
                _, _, _, _ in
            }
            fatalError("incorrect READ_END accepted")
        } catch SensorTransfer.Failure.invalidReply {}
        precondition(!FileManager.default.fileExists(atPath: root.appendingPathComponent("b/data.parquet").path))

        // Both stations remain independent when a phone collects concurrently.
        let first = try ArchiveIO(root: root)
        let second = try ArchiveIO(root: root)
        func wholeScript() -> [ArchiveReply] {
            var frames = script()
            frames[3] = ArchiveReply(kind: "chunk", handle: 7, offset: 0, data: bytes)
            return frames
        }
        let firstPipe = ScriptedPipe(wholeScript())
        let secondPipe = ScriptedPipe(wholeScript())
        async let a: Void = SensorTransfer.collect(pipe: firstPipe, archive: first, station: "c", maxRead: 16384) {
            _, _, _, _ in
        }
        async let b: Void = SensorTransfer.collect(pipe: secondPipe, archive: second, station: "d", maxRead: 16384) {
            _, _, _, _ in
        }
        _ = try await (a, b)
        precondition(FileManager.default.fileExists(atPath: root.appendingPathComponent("c/data.parquet").path))
        precondition(FileManager.default.fileExists(atPath: root.appendingPathComponent("d/data.parquet").path))
        print("iOS transfer: reconstructed interrupted resume, completed-file skip, rate/throttle, offsets, handles, READ_END and concurrent stations passed")
    }
}
