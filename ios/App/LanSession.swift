import Foundation
import Network
import OpenSensorShared
import Synchronization

enum LanFailure: Error {
    case invalidReply, credentialsUnavailable, enrollmentRequired, thingChanged, hardwareChanged, authentication,
        busy, timeout, disconnected

    /// The words for a session that failed or ended. The shared state shows Android's wording.
    var notice: CompanionNotice {
        switch self {
        case .invalidReply: return .verificationFailed
        // A missing or refused token is dropped and fetched again over Bluetooth, silently.
        case .credentialsUnavailable, .enrollmentRequired, .authentication: return .verificationFailed
        case .thingChanged: return .thingChanged
        case .hardwareChanged: return .hardwareChanged
        case .busy: return .busy
        case .timeout, .disconnected: return .syncAborted
        }
    }

    /// The words when the handshake itself failed. A dropped or silent socket then means the
    /// sensor could not be verified, not that a sync stopped.
    static func connectNotice(_ error: Error) -> CompanionNotice {
        switch error as? LanFailure {
        case .some(.timeout), .some(.disconnected), .none: return .verificationFailed
        case .some(let failure): return failure.notice
        }
    }
}

/// Who a collection talks to, read from HELLO or INFO.
struct CollectTarget: Sendable {
    let boot: String?
    let thing: String
    let device: String
    let maxRead: Int32
    let mtu: Int32
}

/// Invariant. Every `IosLink` entry point is thread safe in Kotlin, and `identify` checks its one-time atomic bind.
extension IosLink: @retroactive @unchecked Sendable {
    /// The only way Swift makes a link. Its identity is bound before any other domain can see it.
    static func make(port: any IosLinkPort, overWifi: Bool, identity: SyncIdentity) -> IosLink {
        let link = IosLink(port: port, overWifi: overWifi, address: identity.device)
        link.identify(thing: identity.thing, boot: identity.boot, maxRead: identity.maxRead)
        // Waits for the link to open, then asks for the board template only when no known one matches.
        link.fetchBoardTemplate(dict: identity.dict, desc: identity.desc)
        return link
    }
}

/// Invariant. Kotlin's write callback is safe from any thread and runs once. Debug builds trap a second call.
final class WriteDone: @unchecked Sendable {
    private let done: (KotlinBoolean) -> Void
    private let called = Atomic<Bool>(false)
    init(_ done: @escaping (KotlinBoolean) -> Void) { self.done = done }
    func call(_ ok: Bool) {
        let first = !called.exchange(true, ordering: .relaxed)
        assert(first, "a write completion ran twice")
        guard first else { return }
        done(KotlinBoolean(bool: ok))
    }
}

/// How the shared link writes to and paces this socket. Kotlin calls it from any thread, and each
/// call hops onto the session queue, so writes and pauses keep their call order.
private final class LanPort: NSObject, IosLinkPort, Sendable {
    private weak let session: LanSession?
    let mtu: Int32

    init(session: LanSession, mtu: Int32) {
        self.session = session
        self.mtu = mtu
    }

    func send(frame: Data, done: @escaping (KotlinBoolean) -> Void) {
        guard let session else {
            done(KotlinBoolean(bool: false))
            return
        }
        let reply = WriteDone(done)
        session.send(frame) { reply.call($0) }
    }

    func setReadPaused(paused: Bool) {
        session?.pauseReads(paused)
    }
}

/// One serialized consumer per socket. The actor's executor is the session's own serial queue,
/// which also receives every Network callback and timer, so callbacks enter actor state in order
/// through `assumeIsolated` without spawning a task each. Framing parses an accumulating buffer
/// fed by receives of up to 64 KiB. Unsolicited STATUS and LIVE never enter file replies. They
/// reach the owner through `events` in arrival order, followed by `closed` when the socket ends.
/// After HELLO the session makes one `IosLink`, and every file reply goes to it. Kotlin leases that
/// link for sync, commands, Debug and Device settings, and owns the file protocol and its timeouts.
actor LanSession {
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
    private var replies: [LanReply] = []
    private var replyWaiter: Waiter<LanReply>?
    private var connectWaiter: Waiter<Void>?
    private var sendWaiters: [UUID: Waiter<Void>] = [:]
    private var heartbeat: DispatchSourceTimer?
    private var lastReceived = ProcessInfo.processInfo.systemUptime
    private var liveness = LanLiveness(lastSent: ProcessInfo.processInfo.systemUptime)
    /// True while Kotlin holds a lease, so the keepalive never interleaves with its requests.
    private var collecting = false
    /// True while the shared link's inbox is past its high mark. No receive is outstanding then.
    private var readPaused = false
    private var identity: SyncIdentity?
    private var verifiedInfo: String?
    private var payloadMax = 0
    /// Set once after HELLO. From then on file replies go to the shared link as raw frame bodies.
    private var sink: IosLink?

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
    private static let idleTimeout: TimeInterval = 180

    init(endpoint: NWEndpoint) {
        queue = DispatchSerialQueue(label: "space.opensensor.app.lan-session")
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
        guard hello.kind == "hello", hello.code >= 3, (64...1024).contains(Int(hello.count)),
            let identity = wire.identity(json: hello.json)
        else { throw LanFailure.invalidReply }
        guard identity.device == credential.device else { throw LanFailure.hardwareChanged }
        guard identity.thing == credential.thing else { throw LanFailure.thingChanged }
        frames.cap = Int(hello.count)
        payloadMax = Int(hello.count)
        self.identity = identity
        verifiedInfo = hello.json
        startHeartbeat()
    }

    /// HELLO payload after the token and saved thing/hardware identity were verified.
    func infoJson() -> String? { closed ? nil : verifiedInfo }

    func requestStatus() {
        guard identity != nil, !closed else { return }
        write(wire.statusRequest())
    }

    /// Who is on the other end, as HELLO proved it. Nil before HELLO or after the socket closed.
    func target() -> CollectTarget? {
        guard let identity, !closed else { return nil }
        return CollectTarget(
            boot: identity.boot, thing: identity.thing, device: identity.device, maxRead: identity.maxRead, mtu: Int32(payloadMax + 3))
    }

    /// The shared link for this socket, made once after HELLO and kept until the socket closes. Nil
    /// before HELLO or after the socket closed.
    func link() -> IosLink? {
        if let sink { return sink }
        guard let identity, !closed else { return nil }
        let link = IosLink.make(port: LanPort(session: self, mtu: Int32(payloadMax + 3)), overWifi: true, identity: identity)
        let queue = self.queue
        link.onLeaseChange { @Sendable [weak self] leased in
            let held = leased.boolValue
            guard let session = self else { return }
            queue.async { session.assumeIsolated { $0.collecting = held } }
        }
        sink = link
        replies.removeAll()
        return link
    }

    /// One request body from the shared link. [done] hears whether the socket took it.
    nonisolated func send(_ body: Data, done: @escaping @Sendable (Bool) -> Void) {
        queue.async { [self] in assumeIsolated { $0.write(body, done: done) } }
    }

    /// The shared link's inbox crossed a mark. Calls arrive in order on the session queue.
    nonisolated func pauseReads(_ paused: Bool) {
        queue.async { [self] in
            assumeIsolated {
                $0.readPaused = paused
                if !paused { $0.pump() }
            }
        }
    }

    /// The shared link closed itself (a failed write, an inbox overflow). The socket ends as disconnected,
    /// so its owner reconnects with a fresh link. Safe from any thread.
    nonisolated func linkFailed() {
        queue.async { [self] in assumeIsolated { $0.shutdown(reason: .disconnected, local: true) } }
    }

    /// Safe from any thread. Closing is enqueued on the session queue; pending waits end as disconnected.
    nonisolated func close() {
        queue.async { [self] in assumeIsolated { $0.shutdown(reason: nil) } }
    }

    private static func linkReason(_ reason: LanFailure?, local: Bool) -> Int32 {
        if local { return IosLink.companion.REASON_LOCAL }
        switch reason {
        case .none: return IosLink.companion.REASON_APP
        case .some(.disconnected): return IosLink.companion.REASON_PEER_CLOSED
        case .some: return IosLink.companion.REASON_LOCAL
        }
    }

    /// Session ERROR or BYE frames in place of HELLO end the connection. ERROR 14, or a BYE the
    /// shared token policy reads as rejected or rotated, is `.authentication`, which drops the
    /// Keychain token. BYE sessions-full is `.busy`. Any other BYE, like a bare close, is
    /// `.disconnected` and keeps it (Android `TcpLinkDriver` parity).
    private static func refuse(_ frame: LanReply) throws {
        switch frame.refusal {
        case .tokenRejected: throw LanFailure.authentication
        case .busy: throw LanFailure.busy
        case .peerClosed: throw LanFailure.disconnected
        case .invalid: throw LanFailure.invalidReply
        default: return
        }
    }

    // MARK: Requests and replies

    private func write(_ body: Data, done: (@Sendable (Bool) -> Void)? = nil) {
        guard !closed else {
            done?(false)
            return
        }
        liveness.sent(now: ProcessInfo.processInfo.systemUptime)
        connection.send(
            content: wire.envelope(body: body),
            completion: .contentProcessed { [weak self] error in
                done?(error == nil)
                guard error != nil else { return }
                // This phone failed to write, so Kotlin hears a local fault, not the sensor closing.
                self?.assumeIsolated { $0.shutdown(reason: .disconnected, local: true) }
            })
    }

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

    private func reply(timeout: TimeInterval) async throws -> LanReply {
        try Task.checkCancellation()
        let frame: LanReply
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
        guard !closed, !receiving, !readPaused, replies.count < Self.maxQueuedReplies, connection.state == .ready else { return }
        receiving = true
        connection.receive(minimumIncompleteLength: 1, maximumLength: Self.receiveChunk) {
            [weak self] data, _, complete, error in
            self?.assumeIsolated { $0.received(data, complete: complete, failed: error != nil) }
        }
    }

    private func parse() {
        guard !closed else { return }
        do {
            while !readPaused, replies.count < Self.maxQueuedReplies, let body = try frames.next() {
                guard let reply = decode(body) else { throw LanFailure.invalidReply }
                if reply.kind == "status" { sink?.offerStatus(json: reply.json) }
                if let sink, reply.kind != "status", reply.kind != "live" {
                    // False past the high mark. The link's resume call clears it again.
                    if !sink.offer(frame: body) { readPaused = true }
                } else {
                    route(reply)
                }
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

    private func decode(_ body: Data) -> LanReply? {
        guard let reply = wire.decode(data: body) else { return nil }
        return LanReply(kind: reply.kind, code: reply.code, count: reply.count, json: reply.json, refusal: Self.refusal(reply.refusal))
    }

    private static func refusal(_ shared: HandshakeRefusal) -> LanRefusal {
        switch shared {
        case .tokenRejected: return .tokenRejected
        case .busy: return .busy
        case .peerClosed: return .peerClosed
        case .invalid: return .invalid
        default: return .none
        }
    }

    private func route(_ reply: LanReply) {
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
        write(wire.statusRequest())
    }

    /// [local] when this phone ended it, such as a failed write, which the user still sees as disconnected.
    private func shutdown(reason: LanFailure?, local: Bool = false) {
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
        // Kotlin sees the link end at once, after every frame already offered.
        sink?.linkLost(reason: Self.linkReason(reason, local: local))
        sink = nil
        if let reason { eventSink.yield(.closed(reason)) }
        eventSink.finish()
    }
}
