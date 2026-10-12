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
        pendingLaunchHoldsUntilItsOwnLaunchOrTimeout()
        idleCloseWaitsForTheWorkOnTheLink()
        livePageReopensUntilTheUserLeavesIt()
        aNewRunCountsFromZero()
        print("iOS cancellation checks passed")
    }

    private static func aNewRunCountsFromZero() {
        var counts = RunCounts<String>()
        let first = counts.noticed("aq", done: 3, total: 5, bytes: 300, totalBytes: 500)
        precondition(counts.noticed("aq", done: nil, total: nil, bytes: nil, totalBytes: nil) == first, "a notice keeps the run's counts")
        counts.runStarted("aq")
        precondition(counts.noticed("aq", done: nil, total: nil, bytes: nil, totalBytes: nil) == .init(), "a new run starts at zero")
        precondition(counts.noticed("other", done: 1, total: nil, bytes: nil, totalBytes: nil).done == 1)
    }

    private static func livePageReopensUntilTheUserLeavesIt() {
        var live = LivePage()
        precondition(live.page == nil)
        live.opened("AA-11", allowBluetooth: false)
        precondition(live.page == LivePage.Page(id: "AA-11", allowBluetooth: false), "a Wi-Fi only page reopens over Wi-Fi only")
        live.closed("bb-22")
        precondition(live.page != nil, "another sensor's page closing keeps it")
        live.closed("aa-11")
        precondition(live.page == nil, "leaving the page forgets it")
        live.opened("BB-22", allowBluetooth: true)
        live.disconnected()
        precondition(live.page == nil, "a manual disconnect is never undone on return")
    }

    private static func idleCloseWaitsForTheWorkOnTheLink() {
        var close = IdleClose<String>()
        precondition(close.pageClosed("a", held: false), "an idle link closes with its last page")
        precondition(!close.isWaiting("a"))
        precondition(!close.pageClosed("b", held: true), "a sync keeps its link")
        precondition(!close.settled("b", held: true), "a command after the sync still holds it")
        precondition(close.settled("b", held: false), "the link closes once the work ended")
        precondition(!close.settled("b", held: false), "it closes once")
        _ = close.pageClosed("c", held: true)
        close.forget("c")
        precondition(!close.settled("c", held: false), "opening the sensor again keeps the link")
        precondition(!close.settled("d", held: false), "a link no page let go of stays open")
    }

    private static func pendingLaunchHoldsUntilItsOwnLaunchOrTimeout() {
        var gate = PendingLaunch()
        precondition(!gate.isPending)
        let first = gate.submitted()
        precondition(gate.isPending, "a submitted task holds the next request")
        gate.launched()
        precondition(!gate.isPending, "a launched task frees it")
        let stale = gate.submitted()
        gate.expire(stale)
        precondition(!gate.isPending, "a launch that never came frees it")
        _ = gate.submitted()
        gate.expire(first)
        precondition(gate.isPending, "an older timeout never frees a newer submit")
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
