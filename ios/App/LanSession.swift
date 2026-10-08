import Foundation
import Network
import OpenSensorShared

enum LanFailure: Error, LocalizedError {
    case invalidReply, credentialsUnavailable, enrollmentRequired, identityChanged, authentication, busy, timeout,
        disconnected
    var errorDescription: String? {
        switch self {
        case .invalidReply: return "The sensor sent an invalid sync reply."
        case .credentialsUnavailable: return "Wi-Fi access could not be saved securely on this phone."
        case .enrollmentRequired: return "Connect over Bluetooth and save Wi-Fi access on this phone first."
        case .identityChanged: return "Sensor identity changed. Reconnect over Bluetooth to verify it."
        case .authentication: return "Wi-Fi access expired. Connect over Bluetooth and save access again."
        case .busy: return "The sensor is busy with other phones. Try again shortly."
        case .timeout: return "The sensor did not respond in time. Retry to resume the download."
        case .disconnected: return "Disconnected. Retry to resume the download."
        }
    }
}

/// One serialized consumer per socket. The actor's executor is the session's own serial queue,
/// which also receives every Network callback and timer, so callbacks enter actor state in order
/// through `assumeIsolated` without spawning a task each. Framing parses an accumulating buffer
/// fed by receives of up to 64 KiB. Unsolicited STATUS and LIVE never enter file replies. They
/// reach the owner through `events` in arrival order, followed by `closed` when the socket ends.
actor LanSession: ArchivePipe {
    enum Event: Sendable {
        case push(kind: String, json: String)
        case closed(LanFailure)
    }

    nonisolated let events: AsyncStream<Event>
    private nonisolated let eventSink: AsyncStream<Event>.Continuation
    private let queue: DispatchSerialQueue
    nonisolated var unownedExecutor: UnownedSerialExecutor { queue.asUnownedSerialExecutor() }

    private let connection: NWConnection
    private let wire = SyncWire()
    private var frames = LanFrameBuffer()
    private var receiving = false
    private var closed = false
    private var replies: [ArchiveReply] = []
    private var replyWaiter: Waiter<ArchiveReply>?
    private var connectWaiter: Waiter<Void>?
    private var sendWaiters: [UUID: Waiter<Void>] = [:]
    private var heartbeat: DispatchSourceTimer?
    private var lastReceived = ProcessInfo.processInfo.systemUptime
    private var liveness = LanLiveness(lastSent: ProcessInfo.processInfo.systemUptime)
    private var collecting = false
    private var identity: SyncIdentity?

    private struct Waiter<T> {
        let continuation: CheckedContinuation<T, Error>
        let deadline: DispatchSourceTimer
    }
    private enum Deadline {
        case connect, reply, send(UUID)
    }

    private static let maxQueuedReplies = 32
    private static let receiveChunk = 64 * 1024
    /// Connect, send and HELLO.
    private static let requestTimeout: TimeInterval = 10
    /// File replies between frames, Android's FRAME_TIMEOUT_MS (DeviceSession.kt:528).
    private static let replyTimeout: TimeInterval = 15
    /// OPENED, Android's OPEN_TIMEOUT_MS (DeviceSession.kt:531). The device CRCs the whole file first.
    private static let openTimeout: TimeInterval = 60
    private static let idleTimeout: TimeInterval = 180
    private var openPending = false

    init(endpoint: NWEndpoint) {
        queue = DispatchSerialQueue(label: "earth.walkthru.opensensor.lan-session")
        connection = NWConnection(to: endpoint, using: LanPolicy.connectionParameters())
        (events, eventSink) = AsyncStream.makeStream(of: Event.self, bufferingPolicy: .bufferingNewest(64))
    }

    deinit {
        connection.cancel()
        eventSink.finish()
    }

    func connect(credential: LanCredential) async throws {
        guard !closed, connectWaiter == nil, identity == nil else { throw LanFailure.disconnected }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connectWaiter = Waiter(continuation: continuation, deadline: deadline(after: Self.requestTimeout, .connect))
            connection.stateUpdateHandler = { [weak self] update in
                self?.assumeIsolated { $0.stateChanged(update) }
            }
            connection.start(queue: queue)
        }
        // The Kotlin side zeroes its token copies. Only the outgoing frame holds the token afterwards.
        try await sendRaw(wire.handshake(token: credential.token))
        let hello = try await reply(timeout: Self.requestTimeout)
        try Self.refuse(hello)
        guard hello.kind == "hello", hello.code == 2, (64...1024).contains(Int(hello.count)),
            let identity = wire.identity(json: hello.json)
        else { throw LanFailure.invalidReply }
        guard identity.station == credential.station, identity.device == credential.device else {
            throw LanFailure.identityChanged
        }
        frames.cap = Int(hello.count)
        self.identity = identity
        startHeartbeat()
    }

    /// Per-file failures come back in the summary. The session stays open, like Android.
    func collect(
        archive: ArchiveIO, progress: @escaping @Sendable (TransferUpdate) async -> Void
    ) async throws -> TransferSummary {
        guard let identity, !collecting else { throw LanFailure.disconnected }
        collecting = true
        defer { collecting = false }
        return try await SensorTransfer.collect(
            pipe: self, archive: archive, station: identity.station, maxRead: UInt64(identity.maxRead),
            progress: progress)
    }

    /// Safe from any thread. Closing is enqueued on the session queue; pending waits end as disconnected.
    nonisolated func close() {
        queue.async { [self] in assumeIsolated { $0.shutdown(reason: nil) } }
    }

    // MARK: ArchivePipe

    func list() async throws { try await send(wire.listRequest()) }
    func open(name: String) async throws {
        openPending = true
        try await send(wire.openRequest(name: name))
    }
    func read(handle: Int32, offset: UInt64, length: UInt64) async throws {
        try await send(wire.readRequest(handle: handle, offset: Int64(offset), length: Int64(length)))
    }
    func closeFile(handle: Int32) async throws { try await send(wire.closeRequest(handle: handle)) }
    func receive() async throws -> ArchiveReply {
        let timeout = openPending ? Self.openTimeout : Self.replyTimeout
        openPending = false
        let frame = try await reply(timeout: timeout)
        // An ERROR answering OPEN, READ or CLOSE fails only that file. SensorTransfer decides.
        if frame.kind == "error", frame.code != 14, [SensorTransfer.Op.open, SensorTransfer.Op.read,
            SensorTransfer.Op.close].contains(frame.op)
        {
            return frame
        }
        try Self.refuse(frame)
        return frame
    }

    /// Session ERROR frames (handshake, a full session pool, authentication) end the connection.
    private static func refuse(_ frame: ArchiveReply) throws {
        guard frame.kind == "error" else { return }
        if frame.code == 14 { throw LanFailure.authentication }
        if frame.code == 7 { throw LanFailure.busy }
        throw LanFailure.invalidReply
    }

    // MARK: Requests and replies

    private func send(_ body: Data) async throws { try await sendRaw(wire.envelope(body: body)) }

    private func sendRaw(_ data: Data) async throws {
        guard !closed else { throw LanFailure.disconnected }
        let id = UUID()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            sendWaiters[id] = Waiter(continuation: continuation, deadline: deadline(after: Self.requestTimeout, .send(id)))
            connection.send(
                content: data,
                completion: .contentProcessed { [weak self] error in
                    self?.assumeIsolated { $0.sendFinished(id, failed: error != nil) }
                })
        }
        liveness.sent(now: ProcessInfo.processInfo.systemUptime)
    }

    private func reply(timeout: TimeInterval) async throws -> ArchiveReply {
        try Task.checkCancellation()
        let frame: ArchiveReply
        if !replies.isEmpty {
            frame = replies.removeFirst()
            // Queued frames may be waiting behind the reply bound; parse them and keep reading.
            pump()
        } else {
            guard !closed else { throw LanFailure.disconnected }
            guard replyWaiter == nil else { throw LanFailure.invalidReply }
            frame = try await withCheckedThrowingContinuation { continuation in
                replyWaiter = Waiter(continuation: continuation, deadline: deadline(after: timeout, .reply))
            }
        }
        return frame
    }

    // MARK: Socket callbacks (always on `queue`)

    private func stateChanged(_ update: NWConnection.State) {
        switch update {
        case .ready:
            guard let waiter = connectWaiter else { return }
            connectWaiter = nil
            waiter.deadline.cancel()
            pump()
            waiter.continuation.resume()
        case .failed, .cancelled:
            shutdown(reason: .disconnected)
        default:
            // .waiting and .preparing stay bounded by the connect deadline and TCP connection timeout.
            break
        }
    }

    /// Parses buffered frames while the reply queue has room, then keeps one receive outstanding.
    private func pump() {
        parse()
        guard !closed, !receiving, replies.count < Self.maxQueuedReplies, connection.state == .ready else { return }
        receiving = true
        connection.receive(minimumIncompleteLength: 1, maximumLength: Self.receiveChunk) {
            [weak self] data, _, complete, error in
            self?.assumeIsolated { $0.received(data, complete: complete, failed: error != nil) }
        }
    }

    private func parse() {
        guard !closed else { return }
        do {
            while replies.count < Self.maxQueuedReplies, let body = try frames.next() {
                guard let reply = decode(body) else { throw LanFailure.invalidReply }
                route(reply)
            }
        } catch {
            shutdown(reason: .invalidReply)
        }
    }

    private func received(_ data: Data?, complete: Bool, failed: Bool) {
        receiving = false
        guard !closed else { return }
        if let data, !data.isEmpty {
            lastReceived = ProcessInfo.processInfo.systemUptime
            frames.append(data)
        }
        if failed || complete {
            // A reply that arrived with the end of stream still reaches a waiting request first.
            parse()
            shutdown(reason: .disconnected)
        } else {
            pump()
        }
    }

    private func decode(_ body: Data) -> ArchiveReply? {
        guard let reply = wire.decode(data: body) else { return nil }
        return ArchiveReply(
            kind: reply.kind, name: reply.name, size: reply.size, crc: reply.crc, handle: reply.handle,
            offset: reply.offset, code: reply.code, op: reply.op, count: reply.count,
            data: reply.kind == "chunk" ? reply.payload : nil, json: reply.json)
    }

    private func route(_ reply: ArchiveReply) {
        if reply.kind == "status" || reply.kind == "live" {
            eventSink.yield(.push(kind: reply.kind, json: reply.json))
            return
        }
        if let waiter = replyWaiter {
            replyWaiter = nil
            waiter.deadline.cancel()
            waiter.continuation.resume(returning: reply)
        } else {
            replies.append(reply)
        }
    }

    private func sendFinished(_ id: UUID, failed: Bool) {
        guard let waiter = sendWaiters.removeValue(forKey: id) else { return }
        waiter.deadline.cancel()
        if failed {
            waiter.continuation.resume(throwing: LanFailure.disconnected)
        } else {
            waiter.continuation.resume()
        }
    }

    private func deadline(after seconds: TimeInterval, _ kind: Deadline) -> DispatchSourceTimer {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + seconds)
        timer.setEventHandler { [weak self] in self?.assumeIsolated { $0.expired(kind) } }
        timer.resume()
        return timer
    }

    private func expired(_ kind: Deadline) {
        let waiter: Waiter<Void>?
        switch kind {
        case .reply:
            guard let reply = replyWaiter else { return }
            replyWaiter = nil
            reply.continuation.resume(throwing: LanFailure.timeout)
            shutdown(reason: .timeout)
            return
        case .connect:
            waiter = connectWaiter
            connectWaiter = nil
        case .send(let id):
            waiter = sendWaiters.removeValue(forKey: id)
        }
        guard let waiter else { return }
        waiter.continuation.resume(throwing: LanFailure.timeout)
        shutdown(reason: .timeout)
    }

    /// Outgoing keepalive while idle (the firmware idle timer counts phone traffic) and an
    /// incoming idle bound, checked every 15 seconds.
    private func startHeartbeat() {
        guard heartbeat == nil, !closed else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 15, repeating: 15)
        timer.setEventHandler { [weak self] in self?.assumeIsolated { $0.beat() } }
        timer.resume()
        heartbeat = timer
    }

    private func beat() {
        guard !closed else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if now - lastReceived >= Self.idleTimeout {
            shutdown(reason: .timeout)
            return
        }
        guard liveness.needsKeepalive(now: now, collecting: collecting) else { return }
        liveness.sent(now: now)
        connection.send(
            content: wire.envelope(body: wire.statusRequest()),
            completion: .contentProcessed { [weak self] error in
                guard error != nil else { return }
                self?.assumeIsolated { $0.shutdown(reason: .disconnected) }
            })
    }

    private func shutdown(reason: LanFailure?) {
        guard !closed else { return }
        closed = true
        heartbeat?.cancel()
        heartbeat = nil
        replies.removeAll()
        var waiters = Array(sendWaiters.values)
        sendWaiters.removeAll()
        if let connect = connectWaiter { waiters.append(connect) }
        connectWaiter = nil
        for waiter in waiters {
            waiter.deadline.cancel()
            waiter.continuation.resume(throwing: LanFailure.disconnected)
        }
        if let reply = replyWaiter {
            replyWaiter = nil
            reply.deadline.cancel()
            reply.continuation.resume(throwing: LanFailure.disconnected)
        }
        connection.stateUpdateHandler = nil
        connection.cancel()
        if let reason { eventSink.yield(.closed(reason)) }
        eventSink.finish()
    }
}
