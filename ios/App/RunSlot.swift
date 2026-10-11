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
