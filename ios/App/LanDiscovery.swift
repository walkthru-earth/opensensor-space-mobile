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
    private var sessionDevices: [String: String] = [:]
    private var claims = SensorClaims()
    private var taskIDs: [String: UUID] = [:]
    private var attempts: [String: UUID] = [:]
    private var freshness: [String: Timer] = [:]
    private var tasks: [String: Task<Void, Never>] = [:]
    /// The shared collections (#81). Every Wi-Fi sync runs on its controller for the device, which
    /// owns retries, the run budget and background time, and reconnects through [dial].
    var runs: SensorCollections?
    /// The collection this side started for a service name, from its start until its result. It
    /// outlives a lost socket while the controller retries.
    private var runTokens: [String: UUID] = [:]
    private var runHandles: [String: SyncHandle] = [:]
    /// Runs whose socket closed while the controller reconnects.
    private var paused = Set<String>()
    /// The controller asked for a link to this service. Each answers once.
    private var dialWaiters: [String: [(Bool) -> Void]] = [:]
    private var isBackground = false
    /// Collect all runs as an iOS 26 continued task, so its steps go on off screen (#58).
    var collectOffScreen = false
    /// Off screen, Collect all steps wait unless iOS keeps the pass running.
    private var stepsHeld: Bool { isBackground && !collectOffScreen }
    /// Sessions whose last sensor page closed while a run, a command or a lease held them. They
    /// close once that work ends. Connecting again keeps them.
    private var closeWhenIdle = IdleClose<String>()
    private var progressCounts: [String: (done: Int, total: Int, bytes: Int64, totalBytes: Int64)] = [:]
    private struct Device {
        let endpoint: NWEndpoint
        let station: String
        let device: String
    }
    private let engine: SensorSync
    /// A Today command's handle, while it runs.
    private var handles: [String: SyncHandle] = [:]
    private var commands: [String: (UUID, SensorCommand)] = [:]
    /// The current foreground open may fall back to Bluetooth if this handshake fails.
    private var connectFailures: [String: () -> Void] = [:]
    /// Each open session's shared link, leased by sync, commands, Debug and Device settings.
    private var links: [String: IosLink] = [:]
    /// Collect all steps by service name: the sensor id to report, and the sync outcome once known.
    private var collectSteps: [String: String] = [:]
    private var collectOutcome: [String: Bool] = [:]
    /// Sessions a Collect all step opened. A step closes only these, never a session the user opened.
    private var collectOpened = Set<String>()
    /// A Collect all step ended: the sensor id and whether every listed file is on this phone.
    var onCollectFinished: ((String, Bool) -> Void)?
    /// Auto-sync (#23). Its own browser, so the user's Find Wi-Fi sensors state stays untouched.
    private var backgroundBrowser: NWBrowser?
    private var backgroundBrowseTimer: Timer?
    private var backgroundFound: [String: Device] = [:]
    private var backgroundBrowseDone: (([String], [String]) -> Void)?
    /// Auto-sync steps by service name. Each opens its own session and closes it when it ends.
    private var backgroundRuns: [String: (BackgroundStepResult) -> Void] = [:]
    private var backgroundBytes: [String: Int64] = [:]
    /// An Auto-sync step's progress, files done and files listed.
    var onBackgroundProgress: ((Int, Int) -> Void)?
    var onCredentialRemoved: (() -> Void)?
    init(state: CompanionState, engine: SensorSync) {
        self.state = state
        self.engine = engine
    }

    private func removeCredential(device: String) {
        credentials.remove(device: device)
        onCredentialRemoved?()
    }

    /// The connected Wi-Fi session for this hardware id, if any. Bluetooth sync prefers it.
    func connectedSession(device: String) -> String? {
        sessions.keys.first { name in
            sessionDevices[name] == device.lowercased() && tasks[name] == nil
        }
    }

    /// The open Wi-Fi link for this hardware id, for Debug and Device settings. Kotlin takes a lease
    /// on it, which waits behind any other lease and is refused while a sync runs.
    func link(device: String) -> IosLink? {
        guard let name = sessions.keys.first(where: { sessionDevices[$0] == device.lowercased() }),
            let link = links[name], link.isOpen
        else { return nil }
        return link
    }

    /// Keeps the session's shared link. A link that Kotlin closed (a failed write, an inbox overflow)
    /// ends the socket too, so the next open starts on a fresh link.
    private func adopt(_ link: IosLink, name: String, session: LanSession) {
        guard sessions[name] === session, links[name] !== link else { return }
        let id = ObjectIdentifier(link)
        links[name] = link
        link.whenClosed { @Sendable [weak self, weak session] in
            session?.linkFailed()
            Task { @MainActor [weak self] in
                guard let self, let current = self.links[name], ObjectIdentifier(current) == id else { return }
                self.links[name] = nil
            }
        }
    }

    /// A known sensor may use Wi-Fi immediately after the combined scan. Match both the saved
    /// identity and the Bonjour hints; connect() performs the Keychain and HELLO checks again.
    @discardableResult
    func connectKnown(station: String, device: String, onFailure: (() -> Void)? = nil) -> Bool {
        guard credentials.find(device: device.lowercased())?.station == station else { return false }
        guard let name = devices.first(where: { _, found in
            found.station == station && found.device == device.lowercased()
        })?.key else { return false }
        return connect(name: name, onFailure: onFailure)
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
                let devices = self.sensors(in: results)
                self.devices = devices
                let names = Array(devices.keys)
                self.state.lanSensorsFound(names: names, stations: names.map { devices[$0]?.station ?? "" })
            }
        }
        browser.start(queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 40, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.stop() }
        }
    }

    /// Protocol 2 sensors in a browse result, by service name. Station and hardware id come from TXT.
    private func sensors(in results: Set<NWBrowser.Result>) -> [String: Device] {
        var devices: [String: Device] = [:]
        for result in results {
            guard case .service(let name, let type, let domain, _) = result.endpoint,
                type.trimmingCharacters(in: CharacterSet(charactersIn: ".")) == state.lanServiceType,
                domain == "local.", case .bonjour(let txt) = result.metadata,
                case .string(let station)? = txt.getEntry(for: "station"),
                case .string(let device)? = txt.getEntry(for: "dev"),
                case .string(let proto)? = txt.getEntry(for: "proto"), (Int(proto) ?? 0) >= 3
            else { continue }
            devices[name] = Device(endpoint: result.endpoint, station: station, device: device.lowercased())
        }
        return devices
    }

    @discardableResult
    func connect(name: String, onFailure: (() -> Void)? = nil) -> Bool {
        closeWhenIdle.forget(name)
        if sessions[name] != nil {
            if state.isLanConnected(name: name) { return true }
            if tasks[name] != nil {
                if let onFailure { connectFailures[name] = onFailure }
                return true
            }
            return false
        }
        guard tasks[name] == nil else { return false }
        guard let device = devices[name] else {
            report(name, notice: .lanSensorGone)
            return false
        }
        guard let credential = credentials.find(device: device.device), credential.station == device.station else {
            report(name, notice: LanFailure.enrollmentRequired.notice)
            return false
        }
        guard claims.connect(name: name, device: device.device) else {
            report(name, notice: .hardwareInUse)
            return false
        }
        if let onFailure { connectFailures[name] = onFailure }
        let session = LanSession(endpoint: device.endpoint)
        let attempt = UUID()
        attempts[name] = attempt
        sessions[name] = session
        sessionDevices[name] = device.device
        // One ordered consumer per session. The stream finishes when the socket closes.
        let events = session.events
        Task { [weak self, weak session] in
            for await event in events {
                guard let self, let session, self.sessions[name] === session else { continue }
                switch event {
                case .push(let kind, let json):
                    if kind == "live" { self.liveReceived(name: name, json: json, session: session) }
                    if kind == "status" { self.state.lanStatusReceived(name: name, json: json) }
                case .closed(let error):
                    self.sessionClosed(name: name, error: error)
                }
            }
        }
        report(name, busy: true, notice: .lanConnecting)
        taskIDs[name] = attempt
        tasks[name] = Task { [weak self] in
            guard let self else { return }
            defer { self.finishTask(name, id: attempt) }
            do {
                try await session.connect(credential: credential)
                guard self.attempts[name] == attempt else { return }
                guard self.sessions[name] === session else {
                    self.connectFailures.removeValue(forKey: name)?()
                    return
                }
                self.report(name, connected: true, notice: .lanConnected)
                guard let info = await session.infoJson() else { throw LanFailure.disconnected }
                guard self.attempts[name] == attempt else { return }
                guard self.sessions[name] === session else {
                    self.connectFailures.removeValue(forKey: name)?()
                    return
                }
                self.state.lanInfoReceived(name: name, json: info)
                self.connectFailures[name] = nil
                if let sink = await session.link() { self.adopt(sink, name: name, session: session) }
                await session.requestStatus()
            } catch {
                session.close()
                guard self.attempts[name] == attempt else { return }
                self.sessions[name] = nil
                self.sessionDevices[name] = nil
                self.claims.closeSession(name: name)
                if case LanFailure.authentication = error { self.removeCredential(device: device.device) }
                self.report(name, notice: LanFailure.connectNotice(error))
                let fallback = self.connectFailures.removeValue(forKey: name)
                fallback?()
            }
        }
        return true
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
        let running = runTokens[name] != nil
        // A run keeps going. The controller reconnects through [dial] or ends it with the typed cause.
        if !running { state.lanSyncStopped(name: name) }
        sessions[name] = nil
        sessionDevices[name] = nil
        links[name] = nil
        if let (_, command) = commands.removeValue(forKey: name) {
            state.today.commandInterrupted(command: command)
        }
        claims.closeSession(name: name)
        state.lanReadingExpired(name: name)
        if running {
            paused.insert(name)
            state.lanCollectionPaused(name: name, paused: true)
            report(name, busy: true, notice: .lanPaused)
        } else {
            report(name, notice: .lanDisconnected)
        }
    }

    /// The Sync button and Smart Sync over the open session. A busy link says so instead of doing nothing.
    func sync(name: String, onlyName: String? = nil) {
        guard tasks[name] == nil, sessions[name] != nil else { return }
        if !startRun(name, onlyName: onlyName) { report(name, connected: true, notice: .busyBeforeSync) }
    }

    /// Starts a run iOS interrupted off screen, back on screen. With no open session the shared
    /// controller dials it through [dial]. False when Bonjour has not found the sensor with access.
    @discardableResult
    func resumeSync(device: String, onlyName: String?) -> Bool {
        guard let name = serviceName(device: device), tasks[name] == nil else { return false }
        return startRun(name, onlyName: onlyName)
    }

    /// Starts this service's collection on the shared controller. False while one already runs here.
    @discardableResult
    private func startRun(_ name: String, onlyName: String?) -> Bool {
        guard runTokens[name] == nil, let runs, let device = sessionDevices[name] ?? devices[name]?.device else {
            return false
        }
        let token = UUID()
        runTokens[name] = token
        state.lanSyncStarted(name: name)
        report(name, connected: true, busy: true, notice: .lanListing)
        let handle = runs.collect(
            device: device, onlyName: onlyName, prefer: .lan, background: backgroundRuns[name] != nil,
            progress: { [weak self] update in
                MainActor.assumeIsolated { self?.runProgressed(name, token: token, update) }
            },
            finished: { [weak self] result in
                MainActor.assumeIsolated { self?.runEnded(name, token: token, result) }
            })
        if runTokens[name] == token { runHandles[name] = handle }
        return true
    }

    private func runProgressed(_ name: String, token: UUID, _ update: SyncProgress) {
        guard runTokens[name] == token else { return }
        if paused.remove(name) != nil { state.lanCollectionPaused(name: name, paused: false) }
        if backgroundRuns[name] != nil {
            backgroundBytes[name] = update.receivedBytes
            onBackgroundProgress?(Int(update.filesDone), Int(update.filesTotal))
        }
        report(
            name, connected: sessions[name] != nil, busy: true, notice: .lanSyncing, done: Int(update.filesDone),
            total: Int(update.filesTotal), bytes: update.receivedBytes, rate: update.bytesPerSecond,
            bytesTotal: update.bytesTotal)
    }

    /// The run's one result, after the controller handed the link back.
    private func runEnded(_ name: String, token: UUID, _ result: SyncResult) {
        guard runTokens[name] == token else { return }
        runTokens[name] = nil
        runHandles[name] = nil
        if paused.remove(name) != nil { state.lanCollectionPaused(name: name, paused: false) }
        if collectSteps[name] != nil { collectOutcome[name] = result.end == .complete }
        let device = sessionDevices[name] ?? devices[name]?.device
        if let done = backgroundRuns[name] {
            // An Auto-sync step closes the session it opened.
            let bytes = backgroundBytes[name] ?? 0
            dropSession(name)
            if result.end == .authFailed, let device { removeCredential(device: device) }
            state.lanSyncFinished(name: name, connected: false, result: result)
            backgroundBytes[name] = nil
            backgroundRuns[name] = nil
            done(.ended(result, bytes: bytes))
            return
        }
        if result.keepsLink, sessions[name] != nil, links[name]?.isOpen == true {
            // Files that were not saved fail alone. The session stays open so a retry is one tap.
            state.lanSyncFinished(name: name, connected: true, result: result)
        } else {
            // A stopped or failed link may still hold a write in flight. The next sync starts on a fresh session.
            dropSession(name)
            if result.end == .authFailed, let device { removeCredential(device: device) }
            state.lanSyncFinished(name: name, connected: false, result: result)
        }
        advanceCollect(name)
        closeIfUnheld(name)
    }

    /// Closes this hardware id's sessions once the last sensor page is gone. Work on a session
    /// delays its close until it ends. A Collect all step closes what it opened by itself.
    func closeIdle(device: String) {
        for name in Array(sessions.keys) where sessionDevices[name] == device.lowercased() {
            if closeWhenIdle.pageClosed(name, held: held(name)) { disconnect(name: name) }
        }
    }

    private func closeIfUnheld(_ name: String) {
        if closeWhenIdle.settled(name, held: held(name)) { disconnect(name: name) }
    }

    /// A run, a command, a Collect all or Auto-sync step, a dial or a lease is on this session.
    private func held(_ name: String) -> Bool {
        runTokens[name] != nil || tasks[name] != nil || handles[name] != nil || collectSteps[name] != nil
            || backgroundRuns[name] != nil || dialWaiters[name] != nil || links[name]?.leased == true
    }

    /// Closes the socket without a notice. The caller reports the result.
    private func dropSession(_ name: String) {
        closeWhenIdle.forget(name)
        freshness.removeValue(forKey: name)?.invalidate()
        links[name] = nil
        sessions.removeValue(forKey: name)?.close()
        sessionDevices[name] = nil
        claims.closeSession(name: name)
        state.lanReadingExpired(name: name)
    }

    /// Sends a Today command over the authenticated, idle Wi-Fi session for this hardware id.
    /// The same shared DeviceSession command path is used for Bluetooth. The command takes a lease of
    /// the session's link, so it never interleaves with a Debug or Device settings request.
    @discardableResult
    func command(device: String, command: SensorCommand) -> Bool {
        guard let name = connectedSession(device: device.lowercased()), tasks[name] == nil,
            let session = sessions[name], !state.today.commandBusy
        else { return false }
        let attempt = UUID()
        attempts[name] = attempt
        taskIDs[name] = attempt
        claims.startTask(name: name)
        commands[name] = (attempt, command)
        tasks[name] = Task { [weak self] in
            guard let self else { return }
            defer {
                if self.commands[name]?.0 == attempt { self.commands[name] = nil }
                self.finishTask(name, id: attempt)
            }
            guard let link = await session.link() else {
                if self.commands[name]?.0 == attempt { self.state.today.commandRefused() }
                return
            }
            guard self.commands[name]?.0 == attempt, self.sessions[name] === session else { return }
            self.adopt(link, name: name, session: session)
            self.state.today.commandStarted()
            let slot = RunSlot<CommandResult, SyncHandle>(cancelHandle: { $0.cancel() })
            await withTaskCancellationHandler {
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    let handle = self.engine.command(link: link, command: command, automatic: false) { result in
                        slot.result = result
                        continuation.resume()
                    }
                    self.handles[name] = handle
                    slot.set(handle)
                }
            } onCancel: {
                slot.cancel()
            }
            self.handles[name] = nil
            guard self.commands[name]?.0 == attempt, let ended = slot.result else { return }
            self.state.today.commandFinished(result: ended)
        }
        return true
    }

    /// One Collect all step over Wi-Fi. Opens its own session, syncs, then closes it and reports [id].
    /// A session the user opened is left alone, so the step fails at once instead, as does a step
    /// that cannot start.
    func collect(name: String, id: String) {
        guard !stepsHeld, collectSteps[name] == nil, tasks[name] == nil, sessions[name] == nil, runTokens[name] == nil
        else {
            onCollectFinished?(id, false)
            return
        }
        collectSteps[name] = id
        collectOutcome[name] = nil
        collectOpened.insert(name)
        connect(name: name)
        if tasks[name] == nil { advanceCollect(name) }
    }

    /// Moves a Collect all step on once its connect or sync task ended.
    private func advanceCollect(_ name: String) {
        guard let id = collectSteps[name], tasks[name] == nil, runTokens[name] == nil else { return }
        let opened = collectOpened.contains(name)
        if let ok = collectOutcome.removeValue(forKey: name) {
            collectSteps[name] = nil
            collectOpened.remove(name)
            if opened, sessions[name] != nil { disconnect(name: name) }
            onCollectFinished?(id, ok)
            return
        }
        if sessions[name] != nil, !stepsHeld {
            if startRun(name, onlyName: nil) { return }
            if opened { disconnect(name: name) }
        }
        collectSteps[name] = nil
        collectOpened.remove(name)
        onCollectFinished?(id, false)
    }

    /// Closes every session with this hardware id. Forget calls it.
    func disconnect(device: String) {
        let names = Set(sessions.keys).union(runTokens.keys)
        for name in names where (sessionDevices[name] ?? devices[name]?.device) == device.lowercased() { disconnect(name: name) }
    }

    func disconnect(name: String) {
        close(name: name, notice: .lanDisconnected)
    }

    /// Stops only a user-started transfer. Its result closes the session.
    func cancelInteractiveSync(device: String) {
        for name in Array(runTokens.keys) where (sessionDevices[name] ?? devices[name]?.device) == device.lowercased() &&
            collectSteps[name] == nil && backgroundRuns[name] == nil {
            runHandles[name]?.cancel()
        }
    }

    private func close(name: String, notice: CompanionNotice) {
        closeWhenIdle.forget(name)
        state.lanSyncStopped(name: name)
        attempts[name] = UUID()
        connectFailures[name] = nil
        links[name] = nil
        if let (_, command) = commands.removeValue(forKey: name) {
            state.today.commandInterrupted(command: command)
        }
        // The handles stop the engine's coroutines at once, and closing the socket below ends the
        // link, which ends any lease still on it. The task cancel reaches the same handle.
        runHandles[name]?.cancel()
        handles[name]?.cancel()
        tasks[name]?.cancel()
        claims.closeSession(name: name)
        freshness.removeValue(forKey: name)?.invalidate()
        sessions.removeValue(forKey: name)?.close()
        sessionDevices[name] = nil
        state.lanReadingExpired(name: name)
        resolveDial(name, false)
        report(name, busy: tasks[name] != nil, notice: notice)
    }

    private func finishTask(_ name: String, id: UUID) {
        guard taskIDs[name] == id else { return }
        taskIDs[name] = nil
        tasks[name] = nil
        claims.finishTask(name: name)
        if attempts[name] != id && sessions[name] == nil {
            report(name, busy: runTokens[name] != nil, notice: runTokens[name] != nil ? .lanPaused : .lanDisconnected)
        }
        resolveDial(name, links[name]?.isOpen == true)
        advanceCollect(name)
        closeIfUnheld(name)
    }

    /// Answers the controller's dials of this service.
    private func resolveDial(_ name: String, _ opened: Bool) {
        guard let waiters = dialWaiters.removeValue(forKey: name) else { return }
        waiters.forEach { $0(opened) }
    }

    /// The open Wi-Fi link for this hardware id, for the shared controller.
    func syncLink(device: String) -> IosLink? { link(device: device) }

    /// True while a session with this hardware id is open or opening, for the app or for a run.
    func hasSession(device: String) -> Bool {
        let names = Set(sessions.keys).union(tasks.keys)
        return names.contains { (sessionDevices[$0] ?? devices[$0]?.device) == device.lowercased() }
    }

    /// True when Bonjour found this hardware id with saved Wi-Fi access, so a run can dial it.
    func canDial(device: String) -> Bool { serviceName(device: device) != nil }

    private func serviceName(device: String) -> String? {
        let device = device.lowercased()
        if let name = sessionDevices.first(where: { $0.value == device })?.key { return name }
        return devices.first { _, found in
            found.device == device && credentials.find(device: device)?.station == found.station
        }?.key
    }

    /// The shared controller found no open Wi-Fi link for a run and asks for one. [done] runs once,
    /// true when the session is open and identified.
    func dial(device: String, done: @escaping (Bool) -> Void) {
        if link(device: device) != nil { return done(true) }
        guard let name = serviceName(device: device) else { return done(false) }
        if backgroundRuns[name] != nil {
            openQuiet(name) { opened, _ in done(opened) }
            return
        }
        dialWaiters[name, default: []].append(done)
        if !connect(name: name) {
            resolveDial(name, false)
        } else if tasks[name] == nil {
            resolveDial(name, links[name]?.isOpen == true)
        }
    }

    func enterBackground() {
        isBackground = true
        stop()
        // Runs keep their session. Their background time is the controller's lease.
        for name in Array(sessions.keys)
        where runTokens[name] == nil && backgroundRuns[name] == nil && !continuesOffScreen(name) && dialWaiters[name] == nil {
            disconnect(name: name)
        }
    }

    func resume() {
        isBackground = false
    }

    /// A Collect all step that iOS keeps running off screen as part of a continued task.
    private func continuesOffScreen(_ name: String) -> Bool { collectOffScreen && collectSteps[name] != nil }

    /// Ends every Collect all step now, as when its continued task was cancelled or expired.
    func cancelCollect() {
        for name in Array(collectSteps.keys) {
            if let handle = runHandles[name] { handle.cancel() } else { disconnect(name: name) }
        }
    }

    func suspend() {
        stop()
        for name in Set(sessions.keys).union(runTokens.keys) { disconnect(name: name) }
    }
    /// Reports a typed notice. The shared state picks the words and keeps the counts not given here.
    private func report(
        _ name: String, connected: Bool = false, busy: Bool = false, notice: CompanionNotice, done: Int? = nil,
        total: Int? = nil, bytes: Int64? = nil, rate: Double = 0, bytesTotal: Int64? = nil
    ) {
        let previous = progressCounts[name] ?? (done: 0, total: 0, bytes: Int64(0), totalBytes: Int64(0))
        let progress = (done: done ?? previous.done, total: total ?? previous.total, bytes: bytes ?? previous.bytes,
                        totalBytes: bytesTotal ?? previous.totalBytes)
        progressCounts[name] = progress
        state.lanSessionNoticed(
            name: name, connected: connected, busy: busy, notice: notice, filesDone: Int32(progress.done),
            filesTotal: Int32(progress.total), bytesDone: progress.bytes, bytesPerSecond: rate,
            bytesTotal: progress.totalBytes)
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

/// Auto-sync over Wi-Fi (#23). Only sensors with saved Wi-Fi access, found by `_aqsync._tcp` and
/// matching the saved station, are opened. Requests are the sync engine's LIST, OPEN, READ and CLOSE.
extension LanDiscovery {
    /// Looks for sensors for up to [timeout] seconds, then answers with service names and their TXT
    /// stations. Found sensors are kept so a step can open them.
    func backgroundBrowse(timeout: TimeInterval, done: @escaping ([String], [String]) -> Void) {
        endBackgroundBrowse()
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: state.lanServiceType, domain: "local."), using: .tcp)
        backgroundBrowser = browser
        backgroundBrowseDone = done
        browser.stateUpdateHandler = { [weak self, weak browser] update in
            MainActor.assumeIsolated {
                guard let self, let browser, self.backgroundBrowser === browser else { return }
                if case .failed = update { self.endBackgroundBrowse() }
            }
        }
        browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
            MainActor.assumeIsolated {
                guard let self, let browser, self.backgroundBrowser === browser else { return }
                self.backgroundFound = self.sensors(in: results)
            }
        }
        browser.start(queue: .main)
        backgroundBrowseTimer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.endBackgroundBrowse() }
        }
    }

    private func endBackgroundBrowse() {
        backgroundBrowseTimer?.invalidate()
        backgroundBrowseTimer = nil
        let browser = backgroundBrowser
        backgroundBrowser = nil
        browser?.stateUpdateHandler = nil
        browser?.browseResultsChangedHandler = nil
        browser?.cancel()
        let found = backgroundFound
        backgroundFound = [:]
        for (name, device) in found { devices[name] = device }
        guard let done = backgroundBrowseDone else { return }
        backgroundBrowseDone = nil
        let names = Array(found.keys)
        done(names, names.map { found[$0]?.station ?? "" })
    }

    /// One step of the shared background run. Opens its own session to this service, syncs, closes
    /// it and answers once. A session or run the user holds is left alone and the step is skipped.
    func backgroundSync(name: String, done: @escaping (BackgroundStepResult) -> Void) {
        guard backgroundRuns[name] == nil, tasks[name] == nil, sessions[name] == nil, collectSteps[name] == nil,
            runTokens[name] == nil
        else {
            done(.skipped("in use"))
            return
        }
        guard let device = devices[name] else {
            done(.skipped("not found"))
            return
        }
        guard let credential = credentials.find(device: device.device), credential.station == device.station else {
            done(.skipped("no wifi access"))
            return
        }
        if runs?.isActive(device: device.device) == true {
            done(.skipped("in use"))
            return
        }
        backgroundRuns[name] = done
        backgroundBytes[name] = 0
        openQuiet(name) { [weak self] opened, refused in
            guard let self, self.backgroundRuns[name] != nil, self.runTokens[name] == nil else { return }
            guard opened else {
                // Nothing was sent, so the runner tries the paired Bluetooth link next, as Android does.
                self.finishBackground(name, .skipped(refused ? "wifi access refused" : "connect failed"))
                return
            }
            if !self.startRun(name, onlyName: nil) {
                self.dropSession(name)
                self.finishBackground(name, .skipped("in use"))
            }
        }
    }

    /// Opens a session for unattended work, without the screen's notices. [done] runs once with
    /// whether the link is open and identified, and whether the sensor refused the saved access.
    private func openQuiet(_ name: String, done: @escaping (Bool, Bool) -> Void) {
        guard tasks[name] == nil, sessions[name] == nil, let device = devices[name],
            let credential = credentials.find(device: device.device), credential.station == device.station,
            claims.connect(name: name, device: device.device)
        else { return done(false, false) }
        let session = LanSession(endpoint: device.endpoint)
        let attempt = UUID()
        attempts[name] = attempt
        sessions[name] = session
        sessionDevices[name] = device.device
        let events = session.events
        Task { [weak self, weak session] in
            for await event in events {
                guard let self, let session, self.sessions[name] === session else { continue }
                if case .closed = event {
                    self.sessions[name] = nil
                    self.sessionDevices[name] = nil
                    self.links[name] = nil
                    self.claims.closeSession(name: name)
                }
            }
        }
        taskIDs[name] = attempt
        tasks[name] = Task { [weak self] in
            guard let self else { return }
            defer { self.finishTask(name, id: attempt) }
            do {
                try await session.connect(credential: credential)
                guard self.attempts[name] == attempt, self.sessions[name] === session, let link = await session.link()
                else { throw LanFailure.disconnected }
                self.adopt(link, name: name, session: session)
                done(true, false)
            } catch {
                session.close()
                if self.sessions[name] === session {
                    self.sessions[name] = nil
                    self.sessionDevices[name] = nil
                    self.claims.closeSession(name: name)
                }
                if case LanFailure.authentication = error {
                    self.removeCredential(device: device.device)
                    done(false, true)
                } else {
                    done(false, false)
                }
            }
        }
    }

    private func finishBackground(_ name: String, _ result: BackgroundStepResult) {
        backgroundBytes[name] = nil
        backgroundRuns.removeValue(forKey: name)?(result)
    }

    /// The task expired or the run was cancelled. Every Auto-sync session closes now, without answering.
    func cancelBackground() {
        backgroundBrowseDone = nil
        endBackgroundBrowse()
        for name in Array(backgroundRuns.keys) {
            backgroundRuns[name] = nil
            backgroundBytes[name] = nil
            close(name: name, notice: .lanDisconnected)
        }
    }
}
