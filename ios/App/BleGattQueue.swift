import Foundation

/// The GATT operation queue of one Bluetooth connection. Core Bluetooth allows one outstanding
/// read, subscription or write per peripheral that this app relies on, so operations run one at a
/// time in the order they were queued. Generic over the characteristic so host checks run without
/// a radio. The owner keeps it on the Bluetooth queue.
struct BleGattQueue<Target: AnyObject> {
    enum Operation {
        case read(Target)
        case subscribe(Target)
        /// A request body. Kotlin's writes carry [done], which hears once whether the sensor took it.
        /// A native write (the token request) has no [done].
        case write(Target, Data, done: (@Sendable (Bool) -> Void)?)

        var target: Target {
            switch self {
            case .read(let target), .subscribe(let target), .write(let target, _, _): return target
            }
        }
    }

    /// What a write completion means for the connection.
    enum WriteOutcome: Equatable {
        /// The callback answered nothing in flight, as after a reset. Ignored.
        case stale
        case written
        /// A Kotlin write failed. Every other queued write failed too, and the shared link is poisoned.
        case linkFailed
        /// The native token write failed. The connection closes.
        case nativeFailed
    }

    private(set) var pending: Operation?
    private var operations: [Operation] = []
    /// The port allowed to write. A port from an earlier link or connection is refused.
    private(set) var sender: UInt64?

    /// No operation queued or in flight.
    var isIdle: Bool { pending == nil && operations.isEmpty }

    /// Discovery follow-up replaces whatever was queued. Only reads and subscriptions are queued then.
    mutating func start(_ operations: [Operation]) {
        self.operations = operations
    }

    mutating func append(_ operation: Operation) {
        operations.append(operation)
    }

    /// Lets this port write from now on. Called once per link, before Kotlin can see it.
    mutating func attach(sender: UInt64) {
        self.sender = sender
    }

    /// Queues one Kotlin write. A stale port or a connection without a control characteristic
    /// fails it at once, so a closed link's late ABORT or owed CLOSE never reaches the next link.
    mutating func write(_ body: Data, to target: Target?, from sender: UInt64, done: @escaping @Sendable (Bool) -> Void) {
        guard let target, self.sender == sender else {
            done(false)
            return
        }
        operations.append(.write(target, body, done: done))
    }

    /// The next operation to perform, once nothing is in flight.
    mutating func next() -> Operation? {
        guard pending == nil, !operations.isEmpty else { return nil }
        let operation = operations.removeFirst()
        pending = operation
        return operation
    }

    /// A read completed. False when it answers nothing in flight, as for a notification.
    mutating func finish(read target: Target) -> Bool {
        guard case .read(let expected)? = pending, expected === target else { return false }
        pending = nil
        return true
    }

    /// A subscription completed. False when it answers nothing in flight.
    mutating func finish(subscribe target: Target) -> Bool {
        guard case .subscribe(let expected)? = pending, expected === target else { return false }
        pending = nil
        return true
    }

    /// A write completed. Each [done] runs exactly once, here or in [failAll].
    mutating func finish(write target: Target, ok: Bool) -> WriteOutcome {
        guard case .write(let expected, _, let done)? = pending, expected === target else { return .stale }
        pending = nil
        guard ok else {
            guard let done else { return .nativeFailed }
            done(false)
            failAll()
            return .linkFailed
        }
        done?(true)
        return .written
    }

    /// Fails every queued or in-flight Kotlin write, empties the queue and refuses every port.
    mutating func failAll() {
        let queued = (pending.map { [$0] } ?? []) + operations
        pending = nil
        operations.removeAll()
        for case .write(_, _, let done?) in queued { done(false) }
    }

    /// The connection ended. Nothing it queued survives, and no port may write again.
    mutating func reset() {
        failAll()
        sender = nil
    }
}
