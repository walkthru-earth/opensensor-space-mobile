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

/// Links whose last sensor page closed. A link closes at once when nothing holds it, otherwise
/// when the work holding it ends. Opening the sensor again keeps it.
struct IdleClose<Key: Hashable> {
    private var waiting = Set<Key>()

    func isWaiting(_ key: Key) -> Bool { waiting.contains(key) }

    /// The last page closed. True when the link should close now.
    mutating func pageClosed(_ key: Key, held: Bool) -> Bool {
        waiting.insert(key)
        return settled(key, held: held)
    }

    /// Work on the link ended. True when a waiting close should happen now.
    mutating func settled(_ key: Key, held: Bool) -> Bool {
        guard waiting.contains(key), !held else { return false }
        waiting.remove(key)
        return true
    }

    /// The user opened it again, or the link closed for another reason.
    mutating func forget(_ key: Key) { waiting.remove(key) }
}

/// A shared link whose leases Swift listens to. `IosLink` conforms in the app target.
protocol LeaseWatched: AnyObject {
    /// Adds [listener]. It hears every lease change on the main thread, true while a lease is held.
    func watchLeases(_ listener: @escaping @Sendable (Bool) -> Void)
}

/// Runs [released] when the last lease of [link] ends while [isCurrent] still names it, so a late
/// release of a replaced link is ignored. The listener keeps no reference to the link, which the
/// link itself holds, so an old link is freed.
@MainActor
func whenLeasesEnd(
    of link: some LeaseWatched,
    isCurrent: @escaping @MainActor @Sendable (ObjectIdentifier) -> Bool,
    released: @escaping @MainActor @Sendable () -> Void
) {
    let id = ObjectIdentifier(link)
    link.watchLeases { leased in
        MainActor.assumeIsolated {
            guard !leased, isCurrent(id) else { return }
            released()
        }
    }
}

/// Wi-Fi sessions whose last sensor page closed, and the lease listener that settles them. A session
/// closes at once when nothing holds it, otherwise when the work or the last lease on it ends.
/// Debug, Device settings and Files lease the link straight from Kotlin with no native task, so only
/// the listener hears their last lease end (#111). Opening the sensor again keeps the session.
@MainActor
final class IdleSessions {
    private var waiting = IdleClose<String>()
    private let held: (String) -> Bool
    private let link: (String) -> ObjectIdentifier?
    private let closeNow: (String) -> Void

    /// [held] says whether work or a lease holds a session, [link] names its current shared link, and
    /// [close] closes it.
    init(
        held: @escaping (String) -> Bool, link: @escaping (String) -> ObjectIdentifier?,
        close: @escaping (String) -> Void
    ) {
        self.held = held
        self.link = link
        closeNow = close
    }

    /// [link] became the shared link of session [name]. Its last lease ending settles a waiting close.
    func adopt(_ link: some LeaseWatched, name: String) {
        whenLeasesEnd(
            of: link, isCurrent: { [weak self] id in self?.link(name) == id },
            released: { [weak self] in self?.settle(name) })
    }

    /// The last page of the session closed. It closes now, or once the work holding it ends.
    func pageClosed(_ name: String) {
        if waiting.pageClosed(name, held: held(name)) { closeNow(name) }
    }

    /// Work on the session ended. A waiting close happens now if nothing else holds it.
    func settle(_ name: String) {
        if waiting.settled(name, held: held(name)) { closeNow(name) }
    }

    /// The user opened it again, or the session closed for another reason.
    func forget(_ name: String) { waiting.forget(name) }
}

/// The sensor page the user has open, kept across the background so returning reconnects it.
/// Leaving the page or disconnecting by hand forgets it.
struct LivePage: Equatable {
    struct Page: Equatable {
        let id: String
        let allowBluetooth: Bool
    }

    private(set) var page: Page?

    mutating func opened(_ id: String, allowBluetooth: Bool) {
        page = Page(id: id, allowBluetooth: allowBluetooth)
    }

    /// The last page of [id] closed, or the user disconnected or forgot it.
    mutating func closed(_ id: String) {
        if page?.id.caseInsensitiveCompare(id) == .orderedSame { page = nil }
    }

    /// The user ended the live link from the open page.
    mutating func disconnected() { page = nil }

    /// Whether a resumed run of [device] may take the one Bluetooth link. The user's page wins, so a
    /// page reopened over Bluetooth for another sensor ([pageDevice] is its hardware id, nil when not
    /// known yet) keeps the link and the resume skips Bluetooth.
    func letsResumeUseBluetooth(device: String, pageDevice: String?) -> Bool {
        guard let page, page.allowBluetooth else { return true }
        guard let pageDevice else { return false }
        return pageDevice.caseInsensitiveCompare(device) == .orderedSame
    }
}

/// The sensors the app asked Core Bluetooth to wait for. Core Bluetooth drops pending connects when
/// the radio turns off, so they are kept here and armed again once it is on.
struct ArmedConnects {
    private(set) var ids = Set<String>()

    mutating func arm(_ more: [String]) { ids.formUnion(more) }

    /// The app ended one pending connect, or every one when [id] is nil.
    mutating func release(_ id: String?) {
        if let id { ids.remove(id) } else { ids.removeAll() }
    }

    /// A connection took the sensor, so it no longer waits.
    mutating func adopted(_ id: String) { ids.remove(id) }

    /// The ids to connect again after the radio came on, skipping those still waiting and the current one.
    func rearm(waiting: Set<String>, current: String?) -> [String] {
        ids.filter { !waiting.contains($0) && $0 != current }.sorted()
    }
}

/// The last progress counts of each Wi-Fi run. A notice without counts keeps the run's own, and a
/// new run starts from zero instead of the previous run's totals.
struct RunCounts<Key: Hashable> {
    struct Counts: Equatable {
        var done = 0
        var total = 0
        var bytes: Int64 = 0
        var totalBytes: Int64 = 0
    }

    private var counts: [Key: Counts] = [:]

    mutating func runStarted(_ key: Key) { counts[key] = nil }

    /// Merges the counts given with the ones kept and returns what to show.
    mutating func noticed(_ key: Key, done: Int?, total: Int?, bytes: Int64?, totalBytes: Int64?) -> Counts {
        var next = counts[key] ?? Counts()
        if let done { next.done = done }
        if let total { next.total = total }
        if let bytes { next.bytes = bytes }
        if let totalBytes { next.totalBytes = totalBytes }
        counts[key] = next
        return next
    }
}

/// The sensor a transport's run collects from, kept from its start until it ends. A run outlives its
/// connection while it waits to retry or dials again, so Disconnect and Forget find it here instead
/// of through the connection. The Stop button goes to `SensorCollections.stopUserRun`, which finds
/// the run in the shared controller.
struct OwnedRun {
    private(set) var device: String?

    mutating func started(device: String) { self.device = device }

    mutating func ended() { device = nil }

    /// Disconnect and Forget end any run of the sensor, in any letter case.
    func closes(device: String) -> Bool {
        self.device?.caseInsensitiveCompare(device) == .orderedSame
    }
}
