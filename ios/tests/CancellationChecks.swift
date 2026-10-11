/// Checks the production run slot without a Kotlin framework or app host.
@MainActor
enum CancellationChecks {
    @MainActor
    private final class Handle {
        private(set) var cancellations = 0
        var onCancellation: (() -> Void)?

        func cancel() {
            cancellations += 1
            precondition(cancellations == 1, "a run handle must be cancelled only once")
            let callback = onCancellation
            onCancellation = nil
            callback?()
        }
    }

    static func run() async {
        await alreadyCancelledRunCancelsHandleBeforeSetReturns()
        await immediateCompletionKeepsResultAndCancellation()
        await installedHandleCanBeCancelledFromBackground()
        print("iOS cancellation checks passed")
    }

    private static func alreadyCancelledRunCancelsHandleBeforeSetReturns() async {
        let slot = RunSlot<String, Handle>(cancelHandle: { $0.cancel() })
        let task = Task { @MainActor in
            await withTaskCancellationHandler {
                let handle = Handle()
                slot.set(handle)
                // No actor suspension separates set from this check. A queued cancel hop is too late.
                precondition(handle.cancellations == 1, "an early cancel must reach the handle before set returns")
            } onCancel: {
                slot.cancel()
            }
        }
        // This actor cannot run the child until awaiting below, so its handler starts cancelled.
        task.cancel()
        await task.value
    }

    private static func immediateCompletionKeepsResultAndCancellation() async {
        let slot = RunSlot<String, Handle>(cancelHandle: { $0.cancel() })
        let task = Task { @MainActor in
            await withTaskCancellationHandler {
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    let handle = Handle()
                    // Match a synchronous engine completion that happens before it returns its handle.
                    slot.result = "completed"
                    continuation.resume()
                    slot.set(handle)
                    precondition(handle.cancellations == 1, "immediate completion must not lose an early cancel")
                }
            } onCancel: {
                slot.cancel()
            }
            precondition(slot.result == "completed", "cancellation must preserve the completed result")
        }
        task.cancel()
        await task.value
    }

    private static func installedHandleCanBeCancelledFromBackground() async {
        let slot = RunSlot<String, Handle>(cancelHandle: { $0.cancel() })
        let handle = Handle()
        slot.set(handle)
        precondition(handle.cancellations == 0)
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            handle.onCancellation = { continuation.resume() }
            Task.detached { slot.cancel() }
        }
        precondition(handle.cancellations == 1, "background cancellation must reach the installed handle")
    }
}
