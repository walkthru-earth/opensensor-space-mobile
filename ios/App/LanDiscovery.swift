import Foundation
import Network
import OpenSensorShared

/// Owns discovered endpoints and independent foreground collection sessions.
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
            self.report(name, message: error.localizedDescription)
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
                if case LanFailure.authentication = error { self.credentials.remove(device: device.device) }
                self.report(name, message: error.localizedDescription)
            }
        }
    }

    func sync(name: String) {
        guard tasks[name] == nil, let session = sessions[name] else { return }
        guard activeTransfers < 2 else {
            report(name, connected: true, message: "Two sensors are syncing. Try again when one finishes.")
            return
        }
        let attempt = UUID()
        attempts[name] = attempt
        claims.startTask(name: name)
        activeTransfers += 1
        report(name, connected: true, busy: true, message: "Listing files…")
        taskIDs[name] = attempt
        tasks[name] = Task { [weak self] in
            guard let self else { return }
            defer {
                self.finishTask(name, id: attempt)
                self.activeTransfers -= 1
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
                self.report(name, message: error.localizedDescription)
            }
        }
    }

    func disconnect(name: String) {
        attempts[name] = UUID()
        tasks[name]?.cancel()
        claims.closeSession(name: name)
        freshness.removeValue(forKey: name)?.invalidate()
        sessions.removeValue(forKey: name)?.close()
        state.lanReadingExpired(name: name)
        report(name, busy: tasks[name] != nil, message: "Disconnected. Collected files stay on this phone.")
    }

    private func finishTask(_ name: String, id: UUID) {
        guard taskIDs[name] == id else { return }
        taskIDs[name] = nil
        tasks[name] = nil
        claims.finishTask(name: name)
        if attempts[name] != id && sessions[name] == nil {
            report(name, message: "Disconnected. Collected files stay on this phone.")
        }
    }

    func suspend() {
        stop()
        for name in Array(sessions.keys) { disconnect(name: name) }
    }
    private func report(
        _ name: String, connected: Bool = false, busy: Bool = false, message: String, done: Int = 0, total: Int = 0,
        bytes: Int64 = 0, rate: Double = 0
    ) {
        state.lanSessionChanged(
            name: name, connected: connected, busy: busy, message: message, filesDone: Int32(done),
            filesTotal: Int32(total), bytesDone: bytes, bytesPerSecond: rate)
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
