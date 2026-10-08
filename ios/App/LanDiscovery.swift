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
    init(state: CompanionState) { self.state = state }

    func scan() {
        stop()
        state.lanScanStarted()
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: state.lanServiceType, domain: "local."), using: .tcp)
        self.browser = browser
        browser.stateUpdateHandler = { [weak self, weak browser] update in
            Task { @MainActor in
                guard let self, let browser, self.browser === browser else { return }
                switch update {
                case .waiting: self.state.lanScanWaiting()
                case .ready: self.state.lanScanReady()
                case .failed:
                    self.state.lanScanUnavailable()
                    self.stop()
                default: break
                }
            }
        }
        browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
            Task { @MainActor in
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
                self.state.lanDevicesFound(names: Array(devices.keys))
                for name in Array(self.pendingResume) where devices[name] != nil { self.resumePending(name) }
            }
        }
        browser.start(queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 40, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.stop() }
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
            report(name, message: LanFailure.enrollmentRequired.localizedDescription)
            return
        }
        guard claims.connect(name: name, device: device.device) else {
            report(name, message: "This sensor already has a Wi-Fi connection on this phone.")
            return
        }
        let session = LanSession(endpoint: device.endpoint)
        let attempt = UUID()
        attempts[name] = attempt
        sessions[name] = session
        session.onClosed = { [weak self, weak session] error in
            guard let self, let session, self.sessions[name] === session else { return }
            self.sessions[name] = nil
            self.claims.closeSession(name: name)
            self.state.lanReadingExpired(name: name)
            if self.isBackground, self.collecting.contains(name), self.canResume(error) {
                self.queueResume(name)
            }
            self.report(name, message: self.pendingResume.contains(name) ? self.pauseMessage : error.localizedDescription)
        }
        session.onPush = { [weak self, weak session] kind, json in
            guard let self, let session, self.sessions[name] === session, kind == "live" else { return }
            self.state.lanReadingReceived(name: name, json: json)
            self.freshness[name]?.invalidate()
            self.freshness[name] = Timer.scheduledTimer(withTimeInterval: 35, repeats: false) {
                [weak self, weak session] _ in
                Task { @MainActor in
                    guard let self, let session, self.sessions[name] === session else { return }
                    self.state.lanReadingExpired(name: name)
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
                self.report(name, message: error.localizedDescription)
            }
        }
    }

    func sync(name: String) {
        guard tasks[name] == nil, let session = sessions[name] else { return }
        guard activeTransfers < 2 else {
            report(name, connected: true, message: pendingResume.contains(name)
                ? "Sync queued. Resuming when another sensor finishes."
                : "Two sensors are syncing. Try again when one finishes.")
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
            do {
                let support = try FileManager.default.url(
                    for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                var root = support.appendingPathComponent("SensorArchive", isDirectory: true)
                let archive = try ArchiveIO(root: root)
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try root.setResourceValues(values)
                try await session.collect(archive: archive) { done, total, bytes, rate in
                    guard self.attempts[name] == attempt, self.sessions[name] === session else { return }
                    self.report(
                        name, connected: true, busy: true, message: "Syncing verified files", done: done, total: total,
                        bytes: bytes, rate: rate)
                }
                guard self.attempts[name] == attempt, self.sessions[name] === session else { return }
                self.report(name, connected: true, message: "Sync complete. Verified files saved on this phone.")
            } catch {
                session.close()
                guard self.attempts[name] == attempt else { return }
                self.sessions[name] = nil
                self.claims.closeSession(name: name)
                self.state.lanReadingExpired(name: name)
                if self.isBackground, self.canResume(error) { self.queueResume(name) }
                self.report(name, message: self.pendingResume.contains(name) ? self.pauseMessage : error.localizedDescription)
            }
        }
    }

    func disconnect(name: String) {
        cancelledCollections.insert(name)
        clearResume(name)
        close(name: name, message: "Disconnected. Collected files stay on this phone.")
    }

    private func close(name: String, message: String) {
        attempts[name] = UUID()
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
                : "Disconnected. Collected files stay on this phone.")
        }
        resumePending(name)
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
        "Sync paused. Saved files and partial bytes are kept; resuming when you return."
    }
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
