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

extension Data {
    var wireHex: String { map { String(format: "%02x", $0) }.joined() }
    init?(wireHex: String) {
        guard wireHex.count % 2 == 0 else { return nil }
        var data = Data()
        var index = wireHex.startIndex
        while index < wireHex.endIndex {
            let end = wireHex.index(index, offsetBy: 2)
            guard let byte = UInt8(wireHex[index..<end], radix: 16) else { return nil }
            data.append(byte)
            index = end
        }
        self = data
    }
}

/// One serialized consumer per socket; unsolicited STATUS/LIVE never enter file replies.
@MainActor
final class LanSession {
    private let connection: NWConnection
    private let wire = SyncWire()
    private var cap = 1024
    private var closed = false
    private var pendingOperations: [UUID: () -> Void] = [:]
    private var reader: Task<Void, Never>?
    private var keepalive: Task<Void, Never>?
    private var replies: [SyncReply] = []
    private var readerGate: CheckedContinuation<Void, Never>?
    private var replyWaiter: ((Result<SyncReply, Error>) -> Void)?
    private var liveness = LanLiveness(lastSent: ProcessInfo.processInfo.systemUptime)
    private var collecting = false
    var onClosed: ((Error) -> Void)?
    var onPush: ((String, String) -> Void)?
    private(set) var identity: SyncIdentity?
    init(endpoint: NWEndpoint) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        connection = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
    }

    func connect(credential: LanCredential) async throws {
        try await operation { completion in
            self.connection.stateUpdateHandler = { update in
                Task { @MainActor in
                    switch update {
                    case .ready:
                        self.connection.stateUpdateHandler = nil
                        completion(.success(()))
                    case .failed: completion(.failure(LanFailure.disconnected))
                    case .cancelled: completion(.failure(LanFailure.disconnected))
                    default: break
                    }
                }
            }
            self.connection.start(queue: .main)
        }
        try await sendRaw(Data(wireHex: wire.handshake(tokenHex: credential.token.wireHex))!)
        let hello = try await reply()
        guard hello.kind == "hello", hello.code == 2, (64...1024).contains(Int(hello.count)),
            let identity = wire.identity(json: hello.json)
        else { throw LanFailure.invalidReply }
        guard identity.station == credential.station, identity.device == credential.device else {
            throw LanFailure.identityChanged
        }
        cap = Int(hello.count)
        self.identity = identity
        reader = Task { [weak self] in
            guard let self else { return }
            do {
                while !self.closed {
                    if self.replies.count >= 32 { await withCheckedContinuation { self.readerGate = $0 } }
                    guard !self.closed else { break }
                    let frame = try await self.receiveFrame(idle: true)
                    if frame.kind == "status" || frame.kind == "live" {
                        self.onPush?(frame.kind, frame.json)
                        continue
                    }
                    if let waiter = self.replyWaiter {
                        self.replyWaiter = nil
                        waiter(.success(frame))
                    } else {
                        self.replies.append(frame)
                    }
                }
            } catch {
                if !self.closed { self.close(reason: error) }
            }
        }
        keepalive = Task { [weak self] in
            guard let self else { return }
            do {
                while !self.closed {
                    try await Task.sleep(nanoseconds: 15_000_000_000)
                    if self.liveness.needsKeepalive(
                        now: ProcessInfo.processInfo.systemUptime, collecting: self.collecting)
                    {
                        try await self.send(self.wire.statusRequest())
                    }
                }
            } catch {
                if !self.closed { self.close(reason: error) }
            }
        }
    }

    func send(_ body: String) async throws {
        guard let data = Data(wireHex: wire.envelope(bodyHex: body)) else { throw LanFailure.invalidReply }
        try await sendRaw(data)
    }
    private func sendRaw(_ data: Data) async throws {
        try await operation { completion in
            self.connection.send(
                content: data,
                completion: .contentProcessed { error in
                    completion(error == nil ? .success(()) : .failure(LanFailure.disconnected))
                })
        }
        liveness.sent(now: ProcessInfo.processInfo.systemUptime)
    }
    private func exact(_ length: Int, timeout: TimeInterval = 10) async throws -> Data {
        var bytes = Data()
        while bytes.count < length {
            let remaining = length - bytes.count
            let next: Data = try await operation(timeout: timeout) { completion in
                self.connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) {
                    data, _, complete, error in
                    if error != nil || data?.isEmpty != false {
                        completion(.failure(LanFailure.disconnected))
                    } else {
                        completion(.success(data!))
                    }
                }
            }
            bytes.append(next)

        }
        return bytes
    }
    private func receiveFrame(idle: Bool = false) async throws -> SyncReply {
        let prefix = try await exact(2, timeout: idle ? 180 : 10)
        let count = Int(prefix[0]) | (Int(prefix[1]) << 8)
        guard (1...cap).contains(count), let frame = wire.decode(hex: (try await exact(count)).wireHex) else {
            throw LanFailure.invalidReply
        }
        return frame
    }
    func reply() async throws -> SyncReply {
        try Task.checkCancellation()
        let frame: SyncReply
        if reader == nil {
            frame = try await receiveFrame()
        } else if !replies.isEmpty {
            frame = replies.removeFirst()
            let gate = readerGate
            readerGate = nil
            gate?.resume()
        } else {
            frame = try await withCheckedThrowingContinuation { continuation in
                var completed = false
                let deadline = DispatchSource.makeTimerSource(queue: .main)
                let complete: (Result<SyncReply, Error>) -> Void = { result in
                    guard !completed else { return }
                    completed = true
                    deadline.cancel()
                    deadline.setEventHandler(handler: nil)
                    continuation.resume(with: result)
                }
                replyWaiter = complete
                deadline.schedule(deadline: .now() + 10)
                deadline.setEventHandler { [weak self] in
                    guard !completed else { return }
                    self?.replyWaiter = nil
                    complete(.failure(LanFailure.timeout))
                    self?.close(reason: LanFailure.timeout)
                }
                deadline.resume()
            }
        }
        if frame.kind == "error" {
            if frame.code == 14 { throw LanFailure.authentication }
            if frame.code == 7 { throw LanFailure.busy }
            throw LanFailure.invalidReply
        }
        return frame
    }
    func close(reason: Error? = nil) {
        guard !closed else { return }
        closed = true
        reader?.cancel()
        keepalive?.cancel()
        let gate = readerGate
        readerGate = nil
        gate?.resume()
        let cancel = Array(pendingOperations.values)
        pendingOperations.removeAll()
        for completion in cancel { completion() }
        let waiter = replyWaiter
        replyWaiter = nil
        waiter?(.failure(LanFailure.disconnected))
        connection.stateUpdateHandler = nil
        connection.cancel()
        if let reason { onClosed?(reason) }
    }
    private func operation<T>(timeout: TimeInterval = 10, _ setup: (@escaping (Result<T, Error>) -> Void) -> Void)
        async throws -> T
    {
        guard !closed else { throw LanFailure.disconnected }
        return try await withCheckedThrowingContinuation { continuation in
            let operationID = UUID()
            var completed = false
            let deadline = DispatchSource.makeTimerSource(queue: .main)
            let complete: (Result<T, Error>) -> Void = { result in
                guard !completed else { return }
                completed = true
                deadline.cancel()
                deadline.setEventHandler(handler: nil)
                self.pendingOperations[operationID] = nil
                continuation.resume(with: result)
            }
            pendingOperations[operationID] = { complete(.failure(LanFailure.disconnected)) }
            deadline.schedule(deadline: .now() + timeout)
            deadline.setEventHandler { [weak self] in
                guard !completed else { return }
                complete(.failure(LanFailure.timeout))
                self?.close(reason: LanFailure.timeout)
            }
            deadline.resume()
            setup(complete)
        }
    }

    func collect(archive: ArchiveIO, progress: (Int, Int, Int64, Double) -> Void) async throws {
        guard let identity, !collecting else { throw LanFailure.disconnected }
        collecting = true
        defer { collecting = false }
        try await SensorTransfer.collect(
            pipe: self, archive: archive, station: identity.station, maxRead: UInt64(identity.maxRead),
            progress: progress)
    }
}

extension LanSession: ArchivePipe {
    func list() async throws { try await send(wire.listRequest()) }
    func open(name: String) async throws { try await send(wire.openRequest(name: name)) }
    func read(handle: Int32, offset: UInt64, length: UInt64) async throws {
        try await send(wire.readRequest(handle: handle, offset: Int64(offset), length: Int64(length)))
    }
    func closeFile(handle: Int32) async throws { try await send(wire.closeRequest(handle: handle)) }
    func receive() async throws -> ArchiveReply {
        let frame = try await reply()
        return ArchiveReply(
            kind: frame.kind, name: frame.name, size: frame.size, crc: frame.crc, handle: frame.handle,
            offset: frame.offset, code: frame.code, count: frame.count, data: Data(wireHex: frame.dataHex))
    }
}
