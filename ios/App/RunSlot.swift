import Foundation
import Synchronization

/// Keeps a run's result and handle on main. Cancellation is recorded before any actor hop, so a
/// caller already cancelled when its operation starts also cancels the handle it later creates.
@MainActor
final class RunSlot<Result, Handle: AnyObject> {
    var result: Result?
    private var handle: Handle?
    private let cancelHandle: (Handle) -> Void
    private var cancellationDelivered = false
    private nonisolated let cancellationRequested = Atomic<Bool>(false)

    init(cancelHandle: @escaping (Handle) -> Void) {
        self.cancelHandle = cancelHandle
    }

    func set(_ handle: Handle) {
        precondition(self.handle == nil, "a run has only one handle")
        self.handle = handle
        cancelIfNeeded()
    }

    nonisolated func cancel() {
        cancellationRequested.store(true, ordering: .releasing)
        Task { @MainActor in self.cancelIfNeeded() }
    }

    private func cancelIfNeeded() {
        guard cancellationRequested.load(ordering: .acquiring), !cancellationDelivered, let handle else { return }
        cancellationDelivered = true
        cancelHandle(handle)
    }
}

/// A continued task submitted and not launched yet. A second request waits for it instead of
/// starting work the launch would then refuse. Only the submit a timeout was armed for clears it.
struct PendingLaunch {
    private var token: UUID?

    var isPending: Bool { token != nil }

    /// Records a submit and returns the token its timeout clears.
    mutating func submitted() -> UUID {
        let next = UUID()
        token = next
        return next
    }

    mutating func launched() { token = nil }

    /// The launch for [submit] never came. A newer submit stays pending.
    mutating func expire(_ submit: UUID) {
        if token == submit { token = nil }
    }
}
