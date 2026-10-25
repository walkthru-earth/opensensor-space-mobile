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
        idleCloseWaitsForTheWorkOnTheLink()
        livePageReopensUntilTheUserLeavesIt()
        aNewRunCountsFromZero()
        aUserCancelReachesTheRunThroughAReconnect()
        aLeaseReleaseClosesAWaitingSession()
        aLeaseListenerLetsAReplacedLinkGo()
        onlyTheCurrentLinksLastLeaseCounts()
        print("iOS cancellation checks passed")
    }

    /// #105. The run outlives its connection while the controller waits to retry or dials again,
    /// before INFO named the sensor. Disconnect and Forget route by the run, not the connection. The
    /// Stop button goes to the shared controller, which `SensorCollectionsTest` covers.
    private static func aUserCancelReachesTheRunThroughAReconnect() {
        var run = OwnedRun()
        precondition(!run.closes(device: "aabbccddeeff"), "no run, nothing to close")
        run.started(device: "aabbccddeeff")
        // The connection and its identity are gone now. Nothing here reads them.
        precondition(run.closes(device: "AABBCCDDEEFF"), "Disconnect and Forget reach a run that reconnects, in any case")
        precondition(!run.closes(device: "112233445566"), "Forget of another sensor leaves this run alone")
        run.ended()
        precondition(!run.closes(device: "aabbccddeeff"), "an ended run is not closed again")
    }

    /// A shared link with scripted leases, like `IosLink` calling its listeners on main.
    private final class FakeLink: LeaseWatched {
        private var listeners: [@Sendable (Bool) -> Void] = []
        private(set) var leased = false

        func watchLeases(_ listener: @escaping @Sendable (Bool) -> Void) { listeners.append(listener) }

        func lease(_ held: Bool) {
            leased = held
            listeners.forEach { $0(held) }
        }
    }

    /// The Wi-Fi sessions as `LanDiscovery` keeps them for `IdleSessions`.
    @MainActor
    private final class Sessions {
        var links: [String: FakeLink] = [:]
        var closed: [String] = []
        lazy var idle = IdleSessions(
            held: { [unowned self] in self.links[$0]?.leased == true },
            link: { [unowned self] name in self.links[name].map { ObjectIdentifier($0) } },
            close: { [unowned self] in self.closed.append($0) })

        func adopt(_ link: FakeLink, name: String) {
            links[name] = link
            idle.adopt(link, name: name)
        }
    }

    /// #111. A Debug, Device settings or Files lease ends after the last page closed, with no native
    /// task. The lease listener `IdleSessions.adopt` adds settles the waiting close. A reopened page
    /// or a replaced link keeps the session.
    private static func aLeaseReleaseClosesAWaitingSession() {
        let sessions = Sessions()
        let first = FakeLink()
        sessions.adopt(first, name: "aq")
        first.lease(true)
        sessions.idle.pageClosed("aq")
        precondition(sessions.closed.isEmpty, "a direct lease keeps the session")
        first.lease(false)
        precondition(sessions.closed == ["aq"], "the final lease release closes it")

        let second = FakeLink()
        sessions.adopt(second, name: "aq")
        second.lease(true)
        sessions.idle.pageClosed("aq")
        sessions.idle.forget("aq")
        second.lease(false)
        precondition(sessions.closed == ["aq"], "a reopened page cancels the pending close")
    }

    @MainActor
    private final class Count {
        var value = 0
    }

    /// Only the last lease of the link still current counts. A held lease or a replaced link does not.
    private static func onlyTheCurrentLinksLastLeaseCounts() {
        let released = Count()
        let replaced = FakeLink()
        whenLeasesEnd(of: replaced, isCurrent: { _ in false }, released: { released.value += 1 })
        replaced.lease(false)
        precondition(released.value == 0, "a late release of a replaced link is ignored")

        let current = FakeLink()
        let id = ObjectIdentifier(current)
        whenLeasesEnd(of: current, isCurrent: { $0 == id }, released: { released.value += 1 })
        current.lease(true)
        precondition(released.value == 0, "a lease taken is not a release")
        current.lease(false)
        precondition(released.value == 1, "the current link's last lease ending counts")
    }

    /// #121. The link holds its lease listeners, so a listener that held the link would keep every
    /// replaced link alive. Bluetooth and Wi-Fi both listen through `whenLeasesEnd`.
    private static func aLeaseListenerLetsAReplacedLinkGo() {
        weak var gone: FakeLink?
        do {
            let link = FakeLink()
            gone = link
            whenLeasesEnd(of: link, isCurrent: { _ in true }, released: {})
        }
        precondition(gone == nil, "the lease listener must not keep the link alive")

        let sessions = Sessions()
        weak var replaced: FakeLink?
        do {
            let link = FakeLink()
            replaced = link
            sessions.adopt(link, name: "aq")
        }
        sessions.adopt(FakeLink(), name: "aq")
        precondition(replaced == nil, "a replaced Wi-Fi link is freed")
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

        precondition(live.letsResumeUseBluetooth(device: "dev-a", pageDevice: nil), "no page, the resume may use Bluetooth")
        live.opened("BB-22", allowBluetooth: true)
        precondition(!live.letsResumeUseBluetooth(device: "dev-a", pageDevice: "dev-b"), "the user's page for another sensor keeps Bluetooth")
        precondition(!live.letsResumeUseBluetooth(device: "dev-a", pageDevice: nil), "an unknown page device keeps Bluetooth too")
        precondition(live.letsResumeUseBluetooth(device: "DEV-B", pageDevice: "dev-b"), "a resume of the page's own sensor shares its link")
        live.opened("BB-22", allowBluetooth: false)
        precondition(live.letsResumeUseBluetooth(device: "dev-a", pageDevice: "dev-b"), "a Wi-Fi only page leaves Bluetooth free")
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
