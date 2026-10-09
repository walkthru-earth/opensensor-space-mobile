import Foundation
import Network
import OpenSensorShared
import UIKit

/// Owns discovered endpoints and collection sessions, including bounded background work.
@MainActor
final class LanDiscovery {
    private let state: CompanionState
    private var browser: NWBrowser?
    private var timer: Timer?
    private let credentials = LanCredentials()
    private var devices: [String: Device] = [:]
    private var sessions: [String: LanSession] = [:]
    private var claims = SensorClaims()
    private var taskIDs: [String: UUID] = [:]
    private var attempts: [String: UUID] = [:]
    private var freshness: [String: Timer] = [:]
    private var tasks: [String: Task<Void, Never>] = [:]
    private var activeTransfers = 0
    private var collecting = Set<String>()
    private var pendingResume = Set<String>()
    private var cancelledCollections = Set<String>()
    private var isBackground = false
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var progressCounts: [String: (done: Int, total: Int, bytes: Int64)] = [:]
    private struct Device {
        let endpoint: NWEndpoint
        let station: String
        let device: String
    }
    private let engine: SensorSync
    private var handles: [String: SyncHandle] = [:]
    /// Collect all steps by service name: the sensor id to report, and the sync outcome once known.
    private var collectSteps: [String: String] = [:]
    private var collectOutcome: [String: Bool] = [:]
    /// A Collect all step ended: the sensor id and whether every listed file is on this phone.
    var onCollectFinished: ((String, Bool) -> Void)?
    init(state: CompanionState, engine: SensorSync) {
        self.state = state
        self.engine = engine
    }

    /// The connected Wi-Fi session for this hardware id, if any. Bluetooth sync prefers it.
    func connectedSession(device: String) -> String? {
        sessions.keys.first { name in
            devices[name]?.device == device && tasks[name] == nil
        }
    }

    func scan() {
        stop()
        state.lanScanStarted()
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: state.lanServiceType, domain: "local."), using: .tcp)
        self.browser = browser
        // The browser runs on the main queue, so its callbacks are already main-actor isolated
        // and are handled in delivery order without a task hop each.
        browser.stateUpdateHandler = { [weak self, weak browser] update in
            MainActor.assumeIsolated {
                guard let self, let browser, self.browser === browser else { return }
                switch update {
                case .waiting(let error):
                    // TN3179: only kDNSServiceErr_PolicyDenied identifies a Local Network denial.
                    switch LanPolicy.localNetworkAccess(error) {
                    case .denied: self.state.lanScanAccessDenied()
                    case .unknown: self.state.lanScanWaiting()
                    }
                case .ready: self.state.lanScanReady()
                case .failed:
                    self.state.lanScanUnavailable()
                    self.stop()
                default: break
                }
            }
        }
        browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
            MainActor.assumeIsolated {
                guard let self, let browser, self.browser === browser else { return }
                var devices: [String: Device] = [:]
                for result in results {
                    guard case .service(let name, let type, let domain, _) = result.endpoint,
                        type.trimmingCharacters(in: CharacterSet(charactersIn: ".")) == self.state.lanServiceType,
                        domain == "local.", case .bonjour(let txt) = result.metadata,
                        case .string(let station)? = txt.getEntry(for: "station"),
                        case .string(let device)? = txt.getEntry(for: "dev"),
                        case .string("2")? = txt.getEntry(for: "proto")
                    else { continue }
                    devices[name] = Device(endpoint: result.endpoint, station: station, device: device.lowercased())
                }
                self.devices = devices
                let names = Array(devices.keys)
                self.state.lanSensorsFound(names: names, stations: names.map { devices[$0]?.station ?? "" })
                for name in Array(self.pendingResume) where devices[name] != nil { self.resumePending(name) }
            }
        }
        browser.start(queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 40, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.stop() }
        }
    }

    func connect(name: String) {
        guard tasks[name] == nil, sessions[name] == nil else { return }
        guard let device = devices[name] else {
            report(name, message: "This Wi-Fi sensor is no longer available. Find Wi-Fi sensors again.")
            return
        }
        guard let credential = credentials.find(device: device.device), credential.station == device.station else {
            clearResume(name)
            report(name, notice: LanFailure.enrollmentRequired.notice)
            return
        }
        guard claims.connect(name: name, device: device.device) else {
            report(name, notice: .hardwareInUse)
            return
        }
        let session = LanSession(endpoint: device.endpoint)
        let attempt = UUID()
        attempts[name] = attempt
        sessions[name] = session
        // One ordered consumer per session. The stream finishes when the socket closes.
        let events = session.events
        Task { [weak self, weak session] in
            for await event in events {
                guard let self, let session, self.sessions[name] === session else { continue }
                switch event {
                case .push(let kind, let json):
                    if kind == "live" { self.liveReceived(name: name, json: json, session: session) }
                case .closed(let error):
                    self.sessionClosed(name: name, error: error)
                }
            }
        }
        report(name, busy: true, message: "Connecting on Wi-Fi…")
        taskIDs[name] = attempt
        tasks[name] = Task { [weak self] in
            guard let self else { return }
            defer { self.finishTask(name, id: attempt) }
            do {
                try await session.connect(credential: credential)
                guard self.attempts[name] == attempt, self.sessions[name] === session else { return }
                self.report(name, connected: true, message: "Connected on Wi-Fi. Ready to sync.")
            } catch {
                session.close()
                guard self.attempts[name] == attempt else { return }
                self.sessions[name] = nil
                self.claims.closeSession(name: name)
                self.clearResume(name)
                if case LanFailure.authentication = error { self.credentials.remove(device: device.device) }
                self.report(name, notice: LanFailure.connectNotice(error))
            }
        }
    }

    private func liveReceived(name: String, json: String, session: LanSession) {
        state.lanReadingReceived(name: name, json: json)
        freshness[name]?.invalidate()
        freshness[name] = Timer.scheduledTimer(withTimeInterval: 35, repeats: false) { [weak self, weak session] _ in
            MainActor.assumeIsolated {
                guard let self, let session, self.sessions[name] === session else { return }
                self.state.lanReadingExpired(name: name)
            }
        }
    }

    private func sessionClosed(name: String, error: LanFailure) {
        sessions[name] = nil
        claims.closeSession(name: name)
        state.lanReadingExpired(name: name)
        if isBackground, collecting.contains(name), canResume(error) {
            queueResume(name)
        }
        if pendingResume.contains(name) {
            report(name, message: pauseMessage)
        } else if collecting.contains(name) {
            report(name, notice: error.notice)
        } else {
            report(name, message: closedMessage)
        }
    }

    func sync(name: String) {
        guard tasks[name] == nil, let session = sessions[name] else { return }
        guard activeTransfers < 2 else {
            if pendingResume.contains(name) {
                report(name, connected: true, message: "Sync queued. Resuming when another sensor finishes.")
            } else {
                report(name, connected: true, notice: .busyBeforeSync)
            }
            return
        }
        clearResume(name)
        cancelledCollections.remove(name)
        let attempt = UUID()
        attempts[name] = attempt
        claims.startTask(name: name)
        activeTransfers += 1
        collecting.insert(name)
        beginBackgroundTime()
        report(name, connected: true, busy: true, message: "Listing files…")
        taskIDs[name] = attempt
        tasks[name] = Task { [weak self] in
            guard let self else { return }
            defer {
                self.activeTransfers -= 1
                self.collecting.remove(name)
                self.cancelledCollections.remove(name)
                self.finishTask(name, id: attempt)
                if self.collecting.isEmpty { self.endBackgroundTime() }
                for queued in Array(self.pendingResume) { self.resumePending(queued) }
            }
            let result = await self.collect(name: name, session: session, attempt: attempt)
            if self.collectSteps[name] != nil { self.collectOutcome[name] = result?.end == .complete }
            guard self.attempts[name] == attempt, self.sessions[name] === session else { return }
            if let result, result.keepsLink {
                // Files that were not saved fail alone. The session stays open so a retry is one tap.
                self.state.lanSyncFinished(name: name, connected: true, result: result)
                return
            }
            session.close()
            self.sessions[name] = nil
            self.claims.closeSession(name: name)
            self.state.lanReadingExpired(name: name)
            if result?.end == .authFailed, let device = self.devices[name] {
                self.credentials.remove(device: device.device)
            }
            if self.isBackground, result?.resumable ?? true { self.queueResume(name) }
            if self.pendingResume.contains(name) {
                self.report(name, message: self.pauseMessage)
            } else if let result {
                self.state.lanSyncFinished(name: name, connected: false, result: result)
            } else {
                self.report(name, notice: LanFailure.disconnected.notice)
            }
        }
    }

    /// Runs the shared engine over this session. The engine reads and writes the archive off the
    /// main thread, and only throttled progress and the result reach the main actor.
    /// Nil when the session ended before the sync began.
    private func collect(name: String, session: LanSession, attempt: UUID) async -> SyncResult? {
        guard let target = await session.target() else { return nil }
        let link = SensorLink(overWifi: true, address: target.device, mtu: target.mtu) { body in
            session.enqueue(body)
        }
        guard await session.beginCollect(FrameSink(link)) else { return nil }
        let ended = await withCheckedContinuation { (continuation: CheckedContinuation<SyncEnded, Never>) in
            handles[name] = engine.start(
                link: link, station: target.station, device: target.device, maxRead: target.maxRead,
                progress: { [weak self] update in
                    MainActor.assumeIsolated {
                        guard let self, self.attempts[name] == attempt, self.sessions[name] === session else { return }
                        self.report(
                            name, connected: true, busy: true, message: "Syncing verified files", done: Int(update.filesDone),
                            total: Int(update.filesTotal), bytes: update.fileBytes, rate: update.bytesPerSecond)
                    }
                },
                finished: { continuation.resume(returning: SyncEnded(result: $0)) })
        }
        handles[name] = nil
        await session.endCollect()
        return ended.result
    }

    /// One Collect all step over Wi-Fi. Connects when needed, syncs, then closes the session and
    /// reports [id]. A step that cannot start reports at once.
    func collect(name: String, id: String) {
        guard !isBackground, collectSteps[name] == nil, tasks[name] == nil else {
            onCollectFinished?(id, false)
            return
        }
        collectSteps[name] = id
        collectOutcome[name] = nil
        if sessions[name] == nil { connect(name: name) }
        if tasks[name] == nil { advanceCollect(name) }
    }

    /// Moves a Collect all step on once its connect or sync task ended.
    private func advanceCollect(_ name: String) {
        guard let id = collectSteps[name], tasks[name] == nil else { return }
        if let ok = collectOutcome.removeValue(forKey: name) {
            collectSteps[name] = nil
            if sessions[name] != nil { disconnect(name: name) }
            onCollectFinished?(id, ok)
            return
        }
        if sessions[name] != nil, !isBackground {
            sync(name: name)
            if tasks[name] != nil { return }
            disconnect(name: name)
        }
        collectSteps[name] = nil
        onCollectFinished?(id, false)
    }

    /// Closes every session with this hardware id. Forget calls it.
    func disconnect(device: String) {
        let names = Set(sessions.keys).union(pendingResume)
        for name in names where devices[name]?.device == device.lowercased() { disconnect(name: name) }
    }

    func disconnect(name: String) {
        cancelledCollections.insert(name)
        clearResume(name)
        close(name: name, message: closedMessage)
    }

    private func close(name: String, message: String) {
        attempts[name] = UUID()
        // Swift task cancellation does not reach Kotlin. The handle stops the engine's coroutine.
        handles[name]?.cancel()
        tasks[name]?.cancel()
        claims.closeSession(name: name)
        freshness.removeValue(forKey: name)?.invalidate()
        sessions.removeValue(forKey: name)?.close()
        state.lanReadingExpired(name: name)
        report(name, busy: tasks[name] != nil, message: message)
    }

    private func finishTask(_ name: String, id: UUID) {
        guard taskIDs[name] == id else { return }
        taskIDs[name] = nil
        tasks[name] = nil
        claims.finishTask(name: name)
        if attempts[name] != id && sessions[name] == nil {
            report(name, message: pendingResume.contains(name)
                ? pauseMessage
                : closedMessage)
        }
        resumePending(name)
        advanceCollect(name)
    }

    func enterBackground() {
        isBackground = true
        stop()
        for name in Array(sessions.keys) where !collecting.contains(name) {
            if pendingResume.contains(name) { close(name: name, message: pauseMessage) }
            else { disconnect(name: name) }
        }
        guard !collecting.isEmpty else { return }
        beginBackgroundTime()
        if backgroundTask == .invalid { pauseCollections() }
    }

    func resume() {
        isBackground = false
        endBackgroundTime()
        for name in Array(pendingResume) { resumePending(name) }
    }

    private func resumePending(_ name: String) {
        guard !isBackground, pendingResume.contains(name), tasks[name] == nil else { return }
        if sessions[name] != nil {
            sync(name: name)
        } else {
            // Bonjour service endpoints resolve again; credentials/HELLO still pin the hardware identity.
            connect(name: name)
        }
    }

    private func beginBackgroundTime() {
        guard backgroundTask == .invalid, !collecting.isEmpty else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Sensor collection") { [weak self] in
            // UIKit invokes the expiration handler on the main thread.
            MainActor.assumeIsolated {
                guard let self else { return }
                self.pauseCollections()
                self.endBackgroundTime()
            }
        }
    }

    private func endBackgroundTime() {
        guard backgroundTask != .invalid else { return }
        let task = backgroundTask
        backgroundTask = .invalid
        UIApplication.shared.endBackgroundTask(task)
    }

    private func pauseCollections() {
        for name in collecting where !cancelledCollections.contains(name) {
            queueResume(name)
            close(name: name, message: pauseMessage)
        }
    }

    private var pauseMessage: String {
        "Sync paused. Saved files and partial bytes are kept. It resumes when you return."
    }
    private var closedMessage: String { "Disconnected. Collected files stay on this phone." }
    private func queueResume(_ name: String) {
        guard !cancelledCollections.contains(name) else { return }
        pendingResume.insert(name)
        state.lanCollectionPaused(name: name, paused: true)
    }
    private func clearResume(_ name: String) {
        pendingResume.remove(name)
        state.lanCollectionPaused(name: name, paused: false)
    }
    private func canResume(_ error: Error) -> Bool {
        switch error {
        case LanFailure.disconnected, LanFailure.timeout: return true
        default: return false
        }
    }

    func suspend() {
        stop()
        for name in Array(pendingResume) { disconnect(name: name) }
        for name in Array(sessions.keys) { disconnect(name: name) }
        endBackgroundTime()
    }
    private func report(
        _ name: String, connected: Bool = false, busy: Bool = false, message: String, done: Int? = nil, total: Int? = nil,
        bytes: Int64? = nil, rate: Double = 0
    ) {
        let previous = progressCounts[name] ?? (done: 0, total: 0, bytes: Int64(0))
        let progress = (done: done ?? previous.done, total: total ?? previous.total, bytes: bytes ?? previous.bytes)
        progressCounts[name] = progress
        state.lanSessionChanged(
            name: name, connected: connected, busy: busy, message: message, filesDone: Int32(progress.done),
            filesTotal: Int32(progress.total), bytesDone: progress.bytes, bytesPerSecond: rate)
    }
    /// Reports a typed notice. The shared state shows it in Android's words and keeps the counts.
    private func report(_ name: String, connected: Bool = false, busy: Bool = false, notice: CompanionNotice) {
        let progress = progressCounts[name] ?? (done: 0, total: 0, bytes: Int64(0))
        state.lanSessionNoticed(
            name: name, connected: connected, busy: busy, notice: notice, filesDone: Int32(progress.done),
            filesTotal: Int32(progress.total), bytesDone: progress.bytes)
    }
    func stop() {
        timer?.invalidate()
        timer = nil
        let old = browser
        browser = nil
        old?.stateUpdateHandler = nil
        old?.browseResultsChangedHandler = nil
        old?.cancel()
        state.lanScanStopped()
    }
}

/// Carries the engine's result out of its main-thread callback. The result is immutable.
private final class SyncEnded: @unchecked Sendable {
    let result: SyncResult
    init(result: SyncResult) { self.result = result }
}
