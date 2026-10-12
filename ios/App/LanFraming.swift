import Foundation

/// The LAN socket's own plumbing. The file protocol, retries, verification and archive writes
/// belong to the shared Kotlin engine (`SensorSync`), which both links use.

/// A LAN length prefix that is zero or above the HELLO payload cap.
struct LanFrameError: Error, Equatable {}

/// One decoded session reply or push. Plain Swift values, so replies cross isolation domains safely.
struct LanReply: Sendable {
    let kind: String
    var code: Int32 = 0
    var count: Int32 = 0
    var json = ""
    /// What this frame means in place of HELLO. The shared `SyncReply.refusal` rule decides it.
    var refusal: LanRefusal = .none
}

/// A frame received in place of HELLO. Only `tokenRejected` (ERROR 14 or BYE auth-failed) drops the
/// stored token. `peerClosed` is a BYE without an auth verdict and keeps it, like a bare close.
enum LanRefusal: Sendable {
    case none, tokenRejected, busy, peerClosed, invalid
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
        guard (1...cap).contains(count) else { throw LanFrameError() }
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
