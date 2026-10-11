import CoreBluetooth
import Foundation
import OpenSensorShared
import UIKit

/// BLE reader. Core Bluetooth owns pairing; no firmware-changing requests are sent. User actions run
/// in the foreground. Off screen only a sync or Debug request already running, and an Auto-sync step
/// for a known sensor (#23), keep a link. Each connection gets one shared [IosLink] once INFO named the
/// sensor. Sync, commands, Debug, Device settings and Files take leases of it, so a reply left over
/// from one run is drained before the next run's first request (#58).
@MainActor
final class BluetoothCompanion: NSObject, @preconcurrency CBCentralManagerDelegate, @preconcurrency CBPeripheralDelegate
{
    let state = CompanionState()
    private let wire = SyncWire()
    private let credentials = LanCredentials()
    private var control: CBCharacteristic?
    private var response: CBCharacteristic?
    private var clockAttempted = false
    private var identity: SyncIdentity?
    private var tokenTimer: Timer?
    private var requestingToken = false
    /// The sensor whose last page closed while a write or read was still queued, or while a lease
    /// held its link. Settling closes it.
    private var closeWhenIdle: String?
    /// Held while this side writes `GET_TOKEN` itself, so no Kotlin request shares the link.
    private var nativeLease: IosNativeLease?
    var onCredentialSaved: (() -> Void)?
    /// INFO named the sensor the user opened: peripheral id, advertised name, station and hardware id.
    var onSensorOpened: ((String, String?, String, String) -> SensorOpenResult)?
    /// A Collect all step ended: the sensor id and whether every listed file is on this phone.
    var onCollectFinished: ((String, Bool) -> Void)?
    /// Created when access is already allowed, or when the user asks for it from My devices.
    /// Creating a central manager before that shows the Bluetooth permission prompt.
    private var central: CBCentralManager?
    /// A scan the user asked for before the central manager reported its first state.
    private var scanRequested = false
    /// A sensor the user opened before the central manager reported its first state.
    private var openRequested: String?
    /// The sensor Collect all is copying now. It is closed again when the sync ends.
    private var collectId: String?
    /// Collect all runs as an iOS 26 continued task, so its steps go on off screen (#58).
    var collectOffScreen = false
    /// The Collect all step was cancelled, so it ends instead of starting its sync.
    private var collectCancelled = false
    /// Whether this Collect all step opened the link, so it closes only what it opened.
    private var collectOpened = false
    /// Ends a Collect all step that never started its sync, for example behind a Debug lease that never ended.
    private var collectTimer: Timer?
    private var discovered: [String: CBPeripheral] = [:]
    private var advertisedNames: [String: String] = [:]
    private var current: CBPeripheral?
    private var scanTimer: Timer?
    private var connectionTimer: Timer?
    private var freshnessTimer: Timer?
    private var operations: [Operation] = []
    private var pending: Operation?
    private var foreground = true
    private let engine: SensorSync
    /// This connection's shared link, made once INFO named the sensor. RESPONSE notifications go to it.
    private var ioLink: IosLink?
    /// The sync or command this side started, while it runs.
    private var syncHandle: SyncHandle?
    /// Keeps a sync the user started running for a short while after the app leaves the screen.
    private var syncTask: UIBackgroundTaskIdentifier = .invalid
    private var interactiveFileName: String?
    private var interactiveSyncId: String?
    private var resumePeripheral: CBPeripheral?
    private var resumeTimer: Timer?
    private var resumeWaiting = false
    private var resumeAttempts = 0
    private var resumeStartedAt: Date?
    private let maxForegroundResumeAttempts = 2
    private let foregroundResumeWindow: TimeInterval = 75
    private var responseSubscribed = false
    /// The sensor an Auto-sync step is copying now (#23), and whether that step opened the link.
    private var backgroundId: String?
    private var backgroundOpened = false
    private var backgroundDone: ((BackgroundStepResult) -> Void)?
    private var backgroundBytes: Int64 = 0
    private var backgroundTimer: Timer?
    /// A step asked for before the central manager reported its first state, as after a background launch.
    private var backgroundRequested: (id: String, done: (BackgroundStepResult) -> Void)?
    /// Pending connects to Auto-sync sensors, armed while the app is off screen. iOS completes one
    /// when the sensor comes into range, even with the app suspended or relaunched.
    private var waiting: [String: CBPeripheral] = [:]
    /// An armed pending connect completed. The shared run decides whether that sensor is due.
    var onWake: ((String) -> Void)?
    /// An Auto-sync step's progress, files done and files listed.
    var onBackgroundProgress: ((Int, Int) -> Void)?
    /// Core Bluetooth state restoration, so pending connects survive the app being ended by iOS.
    static let restoreId = "earth.walkthru.opensensor.central"

    /// Links keep working off screen only for work already under way.
    private var active: Bool {
        foreground || syncHandle != nil || ioLink?.leased == true || backgroundId != nil || collectId != nil
    }

    /// A run, a lease or Wi-Fi access setup holds the link.
    private var linkBusy: Bool { syncHandle != nil || ioLink?.leased == true || requestingToken }

    init(engine: SensorSync) {
        self.engine = engine
        super.init()
        let authorization = CBManager.authorization
        state.bluetoothAuthorizationChanged(raw: Int64(authorization.rawValue))
        // Already allowed, so creating the manager shows no prompt and My devices can scan at once.
        if authorization == .allowedAlways { requestAccess() }
    }

    /// Creates the central manager. The first time, iOS asks for Bluetooth access.
    func requestAccess() {
        guard central == nil else { return }
        central = CBCentralManager(
            delegate: self, queue: .main,
            options: [
                CBCentralManagerOptionShowPowerAlertKey: false,
                CBCentralManagerOptionRestoreIdentifierKey: Self.restoreId,
            ])
    }

    /// The connected sensor's hardware id once INFO named it.
    var deviceId: String? { identity?.device }

    private enum Operation {
        case read(CBCharacteristic)
        case subscribe(CBCharacteristic)
        /// A request body. Kotlin's writes carry [done], which hears whether the sensor took it.
        case write(CBCharacteristic, Data, done: ((Bool) -> Void)?)
        var characteristic: CBCharacteristic {
            switch self {
            case .read(let characteristic), .subscribe(let characteristic), .write(let characteristic, _, _):
                return characteristic
            }
        }
    }

    func scan() {
        guard foreground else { return }
        guard let central, central.state == .poweredOn else {
            // My devices scans only once access is allowed. Scanning waits for poweredOn.
            scanRequested = true
            if let existing = self.central {
                centralManagerDidUpdateState(existing)
            } else {
                requestAccess()
            }
            return
        }
        scanRequested = false
        stopScan()
        discovered.removeAll()
        state.scanStarted()
        central.scanForPeripherals(withServices: [CBUUID(string: state.serviceUuid)])
        scanTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.stopScan() }
        }
    }

    func stopScan() {
        scanRequested = false
        scanTimer?.invalidate()
        scanTimer = nil
        if let central, central.state == .poweredOn { central.stopScan() }
        state.scanStopped()
    }

    /// Opens a sensor from My devices, nearby or known. Another open link closes first, because this
    /// app holds one Bluetooth link. Known sensors open through their saved identifier. The caller
    /// cancels Collect all first. A sync or Debug request the user started keeps its link, and the
    /// open is refused instead.
    func open(id: String) {
        guard foreground || collectOffScreen else { return }
        if resumeWaiting { clearInteractiveResume() }
        closeWhenIdle = nil
        if let current, current.identifier.uuidString == id { return }
        if current != nil, syncHandle != nil || ioLink?.leased == true, collectId == nil { return }
        guard let central, central.state == .poweredOn else {
            openRequested = id
            return
        }
        openRequested = nil
        let peripheral =
            discovered[id]
            ?? UUID(uuidString: id).flatMap { central.retrievePeripherals(withIdentifiers: [$0]).first }
        guard let peripheral else { return }
        if current != nil { disconnect() }
        connect(peripheral)
    }

    /// One Collect all step over Bluetooth. Opens the sensor, syncs once it is ready, then closes it.
    /// A link the user holds stays untouched. A link to another sensor, or a sync or Debug request
    /// on this one, fails the step. An idle link to this sensor is synced and left open.
    func collect(id: String) {
        guard foreground || collectOffScreen, !resumeWaiting, !linkBusy, collectId == nil, backgroundId == nil,
            let central, central.state == .poweredOn
        else {
            onCollectFinished?(id, false)
            return
        }
        if let current {
            guard current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame else {
                onCollectFinished?(id, false)
                return
            }
            collectOpened = false
        } else {
            open(id: id)
            guard current?.identifier.uuidString == id else {
                onCollectFinished?(id, false)
                return
            }
            collectOpened = true
        }
        collectId = id
        collectCancelled = false
        collectTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.failCollect() }
        }
        collectWhenReady()
    }

    /// Give the next Collect all sensor the one BLE slot when the current link is idle.
    /// An active sync, command, configuration request or token operation retains its owner.
    func releaseIdleForCollect(next id: String) {
        guard let current, current.identifier.uuidString.caseInsensitiveCompare(id) != .orderedSame,
            !resumeWaiting, !linkBusy, collectId == nil, backgroundId == nil, pending == nil,
            operations.isEmpty
        else { return }
        disconnect()
    }

    private func collectWhenReady() {
        guard collectId != nil else { return }
        // A step cancelled during the automatic clock command ends when that command hands back.
        if collectCancelled { return failCollect() }
        guard !linkBusy, pending == nil, operations.isEmpty else { return }
        // Discovery has settled once the connect timer is gone. A sensor without the sync characteristics
        // or a readable identity cannot be collected, so the step ends instead of waiting.
        if identity == nil || ioLink == nil {
            if connectionTimer == nil, current != nil { failCollect() }
            return
        }
        startSync()
    }

    /// Ends the Collect all step now, as when its continued task was cancelled or expired. A running
    /// sync stops and reports the step, otherwise the step fails at once.
    func cancelCollect() {
        guard collectId != nil else { return }
        collectCancelled = true
        if let syncHandle { syncHandle.cancel() } else { failCollect() }
    }

    /// Ends the Collect all step that has not started its sync. A link the step opened closes, which
    /// reports the failure. A link the user held stays open.
    private func failCollect() {
        guard let id = collectId, syncHandle == nil else { return }
        collectTimer?.invalidate()
        collectTimer = nil
        if collectOpened {
            disconnect()
        } else {
            collectId = nil
            onCollectFinished?(id, false)
        }
    }

    private func connect(_ peripheral: CBPeripheral) {
        guard let central else { return }
        stopScan()
        let id = peripheral.identifier.uuidString
        current = peripheral
        peripheral.delegate = self
        state.connectionStarted(id: id)
        connectionTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.unexpectedLoss(notice: .connectTimedOut)
            }
        }
        central.connect(peripheral)
    }

    /// Ends the connection for a typed reason. The shared state picks the words.
    func disconnect(notice: CompanionNotice = .disconnected) {
        clearInteractiveResume()
        teardown()
        state.connectionClosedNoticed(notice: notice)
    }

    private func clearInteractiveResume() {
        resumeTimer?.invalidate()
        resumeTimer = nil
        resumePeripheral = nil
        resumeWaiting = false
        resumeAttempts = 0
        resumeStartedAt = nil
        interactiveSyncId = nil
        interactiveFileName = nil
    }

    /// Retry only a foreground, user-started transfer whose BLE pipe failed. The shared engine
    /// re-lists files and resumes retained partials; no archive state is copied through Swift.
    @discardableResult
    private func scheduleInteractiveResume(notice: CompanionNotice, result: SyncResult? = nil) -> Bool {
        guard foreground, collectId == nil, backgroundId == nil, let peripheral = current,
            let id = interactiveSyncId, peripheral.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame,
            let central, central.state == .poweredOn, resumeAttempts < maxForegroundResumeAttempts,
            Date().timeIntervalSince(resumeStartedAt ?? Date()) < foregroundResumeWindow
        else { return false }
        if resumeStartedAt == nil { resumeStartedAt = Date() }
        resumeAttempts += 1
        resumeWaiting = true
        resumePeripheral = peripheral
        teardown()
        if let result { state.bluetoothSyncClosed(result: result) }
        else { state.connectionClosedNoticed(notice: notice) }
        resumeTimer?.invalidate()
        resumeTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(resumeAttempts), repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.reconnectInteractiveSync() }
        }
        return true
    }

    private func reconnectInteractiveSync() {
        resumeTimer = nil
        guard resumeWaiting, foreground, interactiveSyncId != nil,
            let started = resumeStartedAt, Date().timeIntervalSince(started) < foregroundResumeWindow,
            let peripheral = resumePeripheral, let central, central.state == .poweredOn,
            collectId == nil, backgroundId == nil, current == nil
        else {
            clearInteractiveResume()
            return
        }
        connect(peripheral)
    }

    private func unexpectedLoss(notice: CompanionNotice) {
        if scheduleInteractiveResume(notice: notice) { return }
        disconnect(notice: notice)
    }

    private func teardown() {
        openRequested = nil
        closeWhenIdle = nil
        // The engine sees the link end at once. Its result for this link is then ignored.
        syncHandle?.cancel()
        syncHandle = nil
        nativeLease?.end()
        nativeLease = nil
        ioLink?.close()
        ioLink = nil
        endSyncTask()
        responseSubscribed = false
        connectionTimer?.invalidate()
        freshnessTimer?.invalidate()
        connectionTimer = nil
        freshnessTimer = nil
        tokenTimer?.invalidate()
        tokenTimer = nil
        requestingToken = false
        identity = nil
        clockAttempted = false
        control = nil
        response = nil
        state.wifiAccessReset()
        failWrites()
        // A command outcome held for its last write reaches Today now that the connection is gone.
        state.today.linkIdle()
        if let peripheral = current {
            current = nil
            peripheral.delegate = nil
            if let central, central.state == .poweredOn { central.cancelPeripheralConnection(peripheral) }
        }
        collectTimer?.invalidate()
        collectTimer = nil
        collectOpened = false
        if let id = collectId {
            // Timed out, refused or lost before the sync ended. Collect all moves on.
            collectId = nil
            onCollectFinished?(id, false)
        }
        if let done = backgroundDone {
            // The link ended before the step finished. Verified files stay, and the run moves on.
            let started = backgroundBytes > 0
            clearBackground()
            done(started ? BackgroundStepResult.lost() : BackgroundStepResult.skipped("connect failed"))
        }
    }

    /// Closes the link to this sensor, if it is open. Forget calls it.
    func close(id: String) {
        if interactiveSyncId?.caseInsensitiveCompare(id) == .orderedSame { clearInteractiveResume() }
        if openRequested == id { openRequested = nil }
        guard let current, current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame else { return }
        disconnect()
    }

    /// Copies every file this phone lacks from the connected sensor through the shared engine.
    /// UTC is synchronized before LIST, OPEN, READ and CLOSE through the operation queue.
    func sync(fileName: String? = nil) {
        // Today's Smart Sync and the Sync button say the link is busy instead of doing nothing.
        if startSync(onlyName: fileName) {
            interactiveSyncId = current?.identifier.uuidString
            interactiveFileName = fileName
            resumeStartedAt = nil
            resumeAttempts = 0
        } else {
            state.today.commandRefused()
        }
    }

    func cancelInteractiveSync() {
        guard collectId == nil, backgroundId == nil else { return }
        let reconnecting = resumeWaiting
        clearInteractiveResume()
        syncHandle?.cancel()
        if reconnecting, current != nil { disconnect() }
    }

    /// Starts the sync when the link is free. Collect all and Auto-sync steps call this from their
    /// idle waiters, so a refusal there stays quiet and the step waits for the next idle link.
    @discardableResult
    private func startSync(onlyName: String? = nil) -> Bool {
        // A lease still held by Debug or Device settings queues the sync behind it on the shared link.
        guard active, clockAttempted, current != nil, let link = ioLink, link.isOpen, let identity, syncHandle == nil,
            !requestingToken
        else { return false }
        // The engine ends a stalled sync itself, so the step timers are no longer needed.
        collectTimer?.invalidate()
        collectTimer = nil
        backgroundTimer?.invalidate()
        backgroundTimer = nil
        state.bluetoothSyncStarted()
        if foreground, collectId == nil, backgroundId == nil { beginSyncTask() }
        syncHandle = engine.startSelected(
            link: link, station: identity.station, device: identity.device, maxRead: identity.maxRead,
            onlyName: onlyName,
            progress: { [weak self] update in
                MainActor.assumeIsolated {
                    guard let self, self.ioLink === link else { return }
                    self.state.bluetoothSyncProgressed(progress: update)
                    if self.backgroundId != nil {
                        self.backgroundBytes = update.receivedBytes
                        self.onBackgroundProgress?(Int(update.filesDone), Int(update.filesTotal))
                    }
                }
            },
            finished: { [weak self] result in
                MainActor.assumeIsolated { self?.syncEnded(result, link: link) }
            })
        return true
    }

    /// Sends automatic UTC synchronization or a confirmed flush through the shared session.
    func command(_ command: SensorCommand, automatic: Bool = false) {
        guard (foreground || automatic), current != nil, let link = ioLink, link.isOpen, syncHandle == nil,
            !requestingToken
        else {
            state.today.commandRefused()
            return
        }
        if !automatic { state.today.commandStarted() }
        syncHandle = engine.command(link: link, command: command, automatic: automatic) { [weak self] result in
            MainActor.assumeIsolated { self?.commandEnded(result, link: link, automatic: automatic) }
        }
    }

    private func commandEnded(_ result: CommandResult, link: IosLink, automatic: Bool) {
        guard ioLink === link else { return }
        syncHandle = nil
        // Today hears an explicit command outcome once the link settles; automatic maintenance
        // remains silent.
        if !automatic { state.today.commandEnded(result: result) }
        // A failed write poisoned the link, so the next request needs a fresh connection.
        guard link.isOpen else {
            disconnect(notice: .sensorDisconnected)
            return
        }
        settle()
    }

    /// Runs what waits for an idle link once no write or read is queued or in flight. First the
    /// outcome of a command for Today, then a Collect all step or an Auto-sync step waiting for the link.
    private func settle() {
        guard !linkBusy, pending == nil, operations.isEmpty else { return }
        if ioLink != nil, !clockAttempted {
            clockAttempted = true
            command(.setTime, automatic: true)
            return
        }
        if resumeWaiting, current?.identifier.uuidString.caseInsensitiveCompare(interactiveSyncId ?? "") == .orderedSame,
            startSync(onlyName: interactiveFileName)
        {
            resumeWaiting = false
            resumePeripheral = nil
            return
        }
        state.today.linkIdle()
        collectWhenReady()
        backgroundWhenReady()
        if let id = closeWhenIdle {
            closeWhenIdle = nil
            closeIdle(id: id)
        }
    }

    /// One Kotlin request body. It joins the GATT queue behind discovery reads and subscriptions,
    /// and [done] hears whether the sensor took it. A closed link's late write, such as its ABORT or
    /// owed CLOSE, fails here instead of reaching the next connection's link.
    fileprivate func send(_ body: Data, from link: IosLink?, done: @escaping (Bool) -> Void) {
        guard let control, let link, ioLink === link, current != nil else {
            done(false)
            return
        }
        operations.append(.write(control, body, done: done))
        performNext()
    }

    /// Fails every queued or in-flight Kotlin write and empties the GATT queue.
    private func failWrites() {
        let queued = (pending.map { [$0] } ?? []) + operations
        pending = nil
        operations.removeAll()
        for case .write(_, _, let done?) in queued { done(false) }
    }

    private func syncEnded(_ result: SyncResult, link: IosLink) {
        guard ioLink === link else { return }
        endSyncTask()
        if result.resumable, scheduleInteractiveResume(notice: .syncAborted, result: result) { return }
        clearInteractiveResume()
        syncHandle = nil
        let collected = collectId
        let opened = collectOpened
        collectId = nil
        collectOpened = false
        let background = backgroundDone
        let backgroundOwned = backgroundOpened
        let bytes = backgroundBytes
        clearBackground()
        if result.keepsLink, link.isOpen {
            state.bluetoothSyncFinished(result: result)
            // Collect all and Auto-sync close what they opened, so the next sensor can take the one
            // link. Off screen nothing stays open once its work ended.
            if (collected != nil && opened) || (background != nil && backgroundOwned) || !foreground { disconnect() }
        } else {
            // A stalled or failed link may still hold a write in flight. Start the next sync on a fresh connection.
            teardown()
            state.bluetoothSyncClosed(result: result)
        }
        if let collected { onCollectFinished?(collected, result.end == .complete) }
        background?(BackgroundStepResult.ended(result, bytes: bytes))
    }

    /// The app left the screen. An idle link closes. A sync, a Debug request or an Auto-sync step
    /// keeps its link and closes it when it ends.
    func suspend() {
        foreground = false
        if resumeWaiting { clearInteractiveResume() }
        scanRequested = false
        stopScan()
        openRequested = nil
        guard syncHandle == nil, ioLink?.leased != true, backgroundId == nil, !(collectOffScreen && collectId != nil)
        else { return }
        disconnect(notice: .resumeLive)
    }

    /// iOS launched the app without a screen, for a background task or a Bluetooth restoration.
    /// Nothing is open yet, so only the flag changes. The scene's resume sets it back.
    func launchedOffScreen() { foreground = false }

    /// Back on screen. Pending connects end, because the user drives the link again.
    func resume() {
        foreground = true
        for (id, peripheral) in waiting where peripheral !== current {
            waiting[id] = nil
            central?.cancelPeripheralConnection(peripheral)
        }
    }

    /// Closes an idle link to this sensor once its last Today, Device or Device settings page is gone
    /// (#16). A sync, Wi-Fi access setup, Collect all or an Auto-sync step keeps it. A command, a
    /// Debug or Device settings lease, or a write still queued delays the close until the link settles.
    func closeIdle(id: String) {
        if resumeWaiting, interactiveSyncId?.caseInsensitiveCompare(id) == .orderedSame {
            clearInteractiveResume()
            if current != nil { disconnect() }
            return
        }
        guard let current, current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame,
            ioLink?.syncRunning != true, collectId == nil, backgroundId == nil, !requestingToken
        else { return }
        guard !linkBusy, pending == nil, operations.isEmpty else {
            closeWhenIdle = id
            return
        }
        disconnect()
    }

    /// True while the user's work holds the link to this sensor. An idle open link is not held.
    func isBusy(id: String) -> Bool {
        if resumeWaiting, interactiveSyncId?.caseInsensitiveCompare(id) == .orderedSame { return true }
        guard let current, current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame else {
            return false
        }
        return linkBusy || collectId != nil
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let notice: CompanionNotice?
        switch central.state {
        case .poweredOn: notice = nil
        case .poweredOff: notice = .bluetoothOff
        case .unauthorized: notice = .bluetoothDenied
        case .unsupported: notice = .bluetoothUnsupported
        default: notice = .bluetoothStarting
        }
        switch central.state {
        case .poweredOn, .unknown, .resetting: break
        default:
            scanRequested = false
            openRequested = nil
        }
        if central.state != .poweredOn, let notice {
            stopScan()
            disconnect(notice: notice)
        }
        switch central.state {
        case .poweredOn, .unknown, .resetting: break
        default: waiting.removeAll()
        }
        // The shared policy maps this to its status and offers Settings only after a denial.
        state.bluetoothAuthorizationChanged(raw: Int64(CBManager.authorization.rawValue))
        state.bluetoothNoticed(available: central.state == .poweredOn, notice: notice)
        state.bluetoothPowerChanged(poweredOff: central.state == .poweredOff)
        if central.state == .poweredOn, let id = openRequested { open(id: id) }
        if central.state == .poweredOn, scanRequested { scan() }
        if central.state == .poweredOn {
            // A restored pending connect that completed while the app was not running.
            for (id, peripheral) in waiting where peripheral.state == .connected && peripheral !== current {
                onWake?(id)
            }
        }
        if let request = backgroundRequested, central.state != .unknown, central.state != .resetting {
            backgroundRequested = nil
            backgroundSync(id: request.id, done: request.done)
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // iOS relaunched the app for a pending connect. Keep the peripherals so the run can use them.
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        for peripheral in peripherals { waiting[peripheral.identifier.uuidString] = peripheral }
    }

    func centralManager(
        _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any], rssi RSSI: NSNumber
    ) {
        guard foreground else { return }
        let id = peripheral.identifier.uuidString
        discovered[id] = peripheral
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? "AQ sensor"
        advertisedNames[id] = name
        state.deviceFound(id: id, name: name, rssi: RSSI.int32Value)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let id = peripheral.identifier.uuidString
        if current !== peripheral, waiting[id] != nil {
            // An armed pending connect completed. On screen the user drives the link, so it ends.
            // Off screen it stays until the run takes it or releases it.
            if foreground {
                releaseWaiting(id: id)
            } else {
                onWake?(id)
            }
            return
        }
        guard current === peripheral, active else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverServices([CBUUID(string: state.serviceUuid)])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if current !== peripheral { waiting[peripheral.identifier.uuidString] = nil }
        guard current === peripheral else { return }
        unexpectedLoss(notice: .connectFailed)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if current !== peripheral { waiting[peripheral.identifier.uuidString] = nil }
        guard current === peripheral else { return }
        // Replies already offered still reach their callers, then the run sees the peer go.
        ioLink?.linkLost(reason: IosLink.companion.REASON_PEER_CLOSED)
        unexpectedLoss(notice: .sensorDisconnected)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard current === peripheral else { return }
        guard error == nil,
            let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: state.serviceUuid) })
        else {
            disconnect(notice: .serviceMissing)
            return
        }
        peripheral.discoverCharacteristics(
            [
                CBUUID(string: state.infoUuid), CBUUID(string: state.liveUuid), CBUUID(string: state.statusUuid),
                CBUUID(string: wire.controlUuid), CBUUID(string: wire.responseUuid),
            ], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard current === peripheral else { return }
        let characteristics = service.characteristics ?? []
        guard error == nil,
            let info = characteristics.first(where: { $0.uuid == CBUUID(string: state.infoUuid) }),
            let live = characteristics.first(where: { $0.uuid == CBUUID(string: state.liveUuid) }),
            let status = characteristics.first(where: { $0.uuid == CBUUID(string: state.statusUuid) }),
            info.properties.contains(.read), live.properties.contains([.read, .notify]),
            status.properties.contains([.read, .notify])
        else {
            disconnect(notice: .characteristicsMissing)
            return
        }
        control = characteristics.first(where: {
            $0.uuid == CBUUID(string: wire.controlUuid) && $0.properties.contains(.write)
        })
        response = characteristics.first(where: {
            $0.uuid == CBUUID(string: wire.responseUuid) && $0.properties.contains(.notify)
        })
        // Serialize discovery follow-up, encrypted subscription and reads through their delegate callbacks.
        operations = [.read(info), .subscribe(live), .read(live), .subscribe(status), .read(status)]
        performNext()
    }

    /// Asks the sensor for its LAN token. The request takes a native lease of the shared link, so it
    /// waits for any Kotlin request and its reply stays on this side.
    func saveWifiAccess() {
        guard foreground, let peripheral = current, identity != nil, let link = ioLink, syncHandle == nil,
            !requestingToken, peripheral.maximumWriteValueLength(for: .withResponse) >= 1
        else { return }
        requestingToken = true
        state.wifiAccessSaving()
        tokenTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.disconnect(notice: .wifiAccessTimedOut)
            }
        }
        link.leaseForNative { [weak self] lease in
            MainActor.assumeIsolated {
                guard let self, let lease, self.requestingToken, self.ioLink === link, let control = self.control else {
                    lease?.end()
                    // A sync took the link first, or it closed. The user can ask again.
                    if let self, self.requestingToken, self.ioLink === link { self.finishTokenRequest(.saveWifiAccess) }
                    return
                }
                self.nativeLease = lease
                self.operations.append(.write(control, self.wire.tokenRequest(), done: nil))
                self.performNext()
            }
        }
    }

    private func finishTokenRequest(_ notice: CompanionNotice) {
        requestingToken = false
        tokenTimer?.invalidate()
        tokenTimer = nil
        nativeLease?.end()
        nativeLease = nil
        state.wifiAccessNoticed(available: true, busy: false, notice: notice)
        settle()
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard current === peripheral, case .write(let expected, _, let done) = pending, expected === characteristic
        else { return }
        if error != nil, let done {
            // The request state on the sensor is unknown, so the shared link is poisoned. The run
            // ends as a lost link, and the connection closes with it.
            pending = nil
            done(false)
            failWrites()
            return
        }
        if error != nil {
            disconnect(notice: .tokenFailed)
            return
        }
        pending = nil
        done?(true)
        performNext()
        settle()
    }

    private func performNext() {
        guard pending == nil, let peripheral = current, !operations.isEmpty else { return }
        let operation = operations.removeFirst()
        pending = operation
        switch operation {
        case .read(let characteristic): peripheral.readValue(for: characteristic)
        case .subscribe(let characteristic): peripheral.setNotifyValue(true, for: characteristic)
        case .write(let characteristic, let data, _): peripheral.writeValue(data, for: characteristic, type: .withResponse)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?
    ) {
        guard current === peripheral else { return }
        guard error == nil, characteristic.isNotifying else {
            disconnect(notice: .notifyFailed)
            return
        }
        if characteristic.uuid == CBUUID(string: wire.responseUuid) { responseSubscribed = true }
        if case .subscribe(let expected) = pending, expected === characteristic {
            pending = nil
            performNext()
            settle()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard current === peripheral, active else { return }
        if characteristic.uuid == CBUUID(string: wire.responseUuid) {
            guard error == nil, let data = characteristic.value else { return }
            // Only the token reply stays here. Every other frame goes to the shared link, which
            // drains replies a cancelled run left behind. Bluetooth cannot pause notifications, and
            // the read window bounds them, so a full inbox is left to poison the link.
            guard nativeLease != nil, let reply = wire.decode(data: data), reply.answersToken else {
                _ = ioLink?.offer(frame: data)
                return
            }
            if reply.kind == "token", let identity {
                do {
                    try credentials.save(
                        LanCredential(
                            station: identity.station, device: identity.device,
                            peripheral: peripheral.identifier.uuidString, token: reply.payload, port: Int(reply.port)))
                    finishTokenRequest(.tokenStored)
                    onCredentialSaved?()
                } catch { finishTokenRequest(LanFailure.credentialsUnavailable.notice) }
            } else {
                finishTokenRequest(.tokenRefused)
            }
            return
        }
        guard error == nil, let data = characteristic.value, let json = String(data: data, encoding: .utf8) else {
            disconnect(notice: .readFailed)
            return
        }
        switch characteristic.uuid {
        case CBUUID(string: state.infoUuid):
            guard let observed = wire.identity(json: json) else {
                disconnect(notice: .infoInvalid)
                return
            }
            let id = peripheral.identifier.uuidString
            guard let result = onSensorOpened?(id, advertisedNames[id] ?? peripheral.name, observed.station, observed.device) else {
                disconnect(notice: .infoInvalid)
                return
            }
            guard result.accepted else {
                disconnect(notice: result.notice ?? .infoInvalid)
                return
            }
            guard state.receiveInfo(json: json) else {
                disconnect(notice: .infoInvalid)
                return
            }
            identity = observed
            openLink(peripheral, identity: observed)
            state.wifiAccessNoticed(
                available: identity != nil && control != nil && response != nil, busy: false,
                notice: .saveWifiAccess)
            state.bluetoothSyncAvailable(available: identity != nil && control != nil && response != nil)
        case CBUUID(string: state.liveUuid):
            state.receiveLive(json: json)
            freshnessTimer?.invalidate()
            freshnessTimer = Timer.scheduledTimer(withTimeInterval: 35, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated { self?.state.readingExpired() }
            }
        case CBUUID(string: state.statusUuid):
            state.receiveStatus(json: json)
            ioLink?.offerStatus(json: json)
        default: return
        }
        if case .read(let expected) = pending, expected === characteristic {
            pending = nil
            if operations.isEmpty {
                connectionTimer?.invalidate()
                connectionTimer = nil
            }
            performNext()
            settle()
        }
    }
}

extension BluetoothCompanion {
    /// The connected sensor's shared link for Debug, Device settings and Files. Each takes a lease,
    /// so its requests queue behind a command on the same session, and a running sync refuses them.
    /// Nil when another sensor is connected or the link is not up.
    func link(device: String) -> IosLink? {
        guard foreground, current != nil, let identity, identity.device == device, let ioLink, ioLink.isOpen else {
            return nil
        }
        return ioLink
    }

    /// Makes this connection's shared link once INFO named the sensor, and subscribes to RESPONSE
    /// before its first write.
    fileprivate func openLink(_ peripheral: CBPeripheral, identity: SyncIdentity) {
        guard ioLink == nil, control != nil, let response else { return }
        let mtu = Int32(peripheral.maximumWriteValueLength(for: .withResponse) + 3)
        let port = BlePort(companion: self, mtu: mtu)
        let link = IosLink(port: port, overWifi: false, address: identity.device)
        // Set on main before any write, which hops to main before it reads this.
        port.link = link
        link.identify(station: identity.station, boot: identity.boot, maxRead: identity.maxRead)
        link.onLeaseChange { [weak self] leased in
            // Kotlin calls this on the main thread.
            MainActor.assumeIsolated {
                guard let self, self.ioLink === link, !leased.boolValue else { return }
                self.leaseEnded()
            }
        }
        let id = ObjectIdentifier(link)
        link.whenClosed { [weak self] in
            Task { @MainActor in self?.linkClosed(id) }
        }
        ioLink = link
        if !responseSubscribed {
            operations.append(.subscribe(response))
            performNext()
        }
    }

    /// A lease ended. Work waiting for an idle link runs, and off screen an idle link closes.
    private func leaseEnded() {
        settle()
        if !foreground, current != nil, !linkBusy, backgroundId == nil, collectId == nil { disconnect() }
    }

    /// The shared link closed or was poisoned while the connection is still up. A running sync ends
    /// with it and closes the connection itself. Otherwise the connection closes now.
    private func linkClosed(_ id: ObjectIdentifier) {
        guard let ioLink, ObjectIdentifier(ioLink) == id, syncHandle == nil else { return }
        disconnect(notice: .sensorDisconnected)
    }

    /// A short background task for a sync the user started, so leaving the screen does not cut it.
    fileprivate func beginSyncTask() {
        guard syncTask == .invalid else { return }
        syncTask = UIApplication.shared.beginBackgroundTask(withName: "Bluetooth sync") { [weak self] in
            MainActor.assumeIsolated {
                self?.syncHandle?.cancel()
                self?.endSyncTask()
            }
        }
    }

    fileprivate func endSyncTask() {
        guard syncTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(syncTask)
        syncTask = .invalid
    }
}

/// The Bluetooth side of one [IosLink]. Kotlin calls it from any thread, so each write hops to the
/// main queue, where Core Bluetooth runs, and joins the GATT queue in call order.
private final class BlePort: NSObject, IosLinkPort, @unchecked Sendable {
    private weak var companion: BluetoothCompanion?
    /// The link this port belongs to. Written and read on the main thread only.
    weak var link: IosLink?
    let mtu: Int32

    init(companion: BluetoothCompanion, mtu: Int32) {
        self.companion = companion
        self.mtu = mtu
    }

    func send(frame: Data, done: @escaping (KotlinBoolean) -> Void) {
        let reply = WriteDone(done)
        DispatchQueue.main.async { [weak companion, weak self] in
            MainActor.assumeIsolated {
                guard let companion else {
                    reply.call(false)
                    return
                }
                companion.send(frame, from: self?.link) { reply.call($0) }
            }
        }
    }

    /// Notifications cannot be paused. The read window the engine asks for bounds them.
    func setReadPaused(paused: Bool) {}
}

/// Auto-sync over Bluetooth (#23). Only sensors this phone already read over an encrypted link are
/// opened, by their saved peripheral identifier. iOS shows no pairing prompt off screen, so a sensor
/// whose bond is gone fails the step instead of asking.
extension BluetoothCompanion {
    /// One step of the shared background run. Adopts an idle link to this sensor, or a pending connect
    /// that completed, or opens the saved peripheral. Answers once, with no ids or secrets in the result.
    func backgroundSync(id: String, done: @escaping (BackgroundStepResult) -> Void) {
        guard backgroundDone == nil, backgroundRequested == nil, collectId == nil, !resumeWaiting else {
            done(BackgroundStepResult.skipped("busy"))
            return
        }
        guard let central else {
            done(BackgroundStepResult.skipped("bluetooth not allowed"))
            return
        }
        if central.state == .unknown || central.state == .resetting {
            backgroundRequested = (id, done)
            return
        }
        guard central.state == .poweredOn else {
            done(BackgroundStepResult.skipped("bluetooth off"))
            return
        }
        if let current {
            // The one link is in use for another sensor, or for work the user started.
            guard current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame, !linkBusy else {
                done(BackgroundStepResult.skipped("in use"))
                return
            }
            backgroundOpened = false
        } else {
            let peripheral =
                waiting.removeValue(forKey: id)
                ?? UUID(uuidString: id).flatMap { central.retrievePeripherals(withIdentifiers: [$0]).first }
            guard let peripheral else {
                done(BackgroundStepResult.skipped("unknown sensor"))
                return
            }
            backgroundOpened = true
            if peripheral.state == .connected {
                adopt(peripheral)
            } else {
                connect(peripheral)
            }
        }
        backgroundId = id
        backgroundDone = done
        backgroundBytes = 0
        backgroundTimer = Timer.scheduledTimer(withTimeInterval: 90, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.failBackground() }
        }
        backgroundWhenReady()
    }

    /// A pending connect already reached the sensor. Discovery starts as after a fresh connect.
    private func adopt(_ peripheral: CBPeripheral) {
        current = peripheral
        peripheral.delegate = self
        state.connectionStarted(id: peripheral.identifier.uuidString)
        connectionTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.disconnect() }
        }
        peripheral.discoverServices([CBUUID(string: state.serviceUuid)])
    }

    fileprivate func backgroundWhenReady() {
        guard backgroundId != nil, !linkBusy, pending == nil, operations.isEmpty else { return }
        if identity == nil || ioLink == nil {
            if connectionTimer == nil, current != nil { failBackground() }
            return
        }
        startSync()
    }

    /// Ends a step that never started its sync. A link the step opened closes, which reports it.
    private func failBackground() {
        guard let done = backgroundDone, syncHandle == nil else { return }
        if backgroundOpened {
            disconnect()
        } else {
            clearBackground()
            done(BackgroundStepResult.skipped("not ready"))
        }
    }

    fileprivate func clearBackground() {
        backgroundTimer?.invalidate()
        backgroundTimer = nil
        backgroundId = nil
        backgroundDone = nil
        backgroundOpened = false
    }

    /// The task expired or the run was cancelled. The step ends at once and a link it opened closes.
    func cancelBackground() {
        backgroundRequested = nil
        guard backgroundDone != nil else { return }
        clearBackground()
        disconnect()
    }

    /// Arms pending connects to these sensors while the app is off screen. They never time out. iOS
    /// completes one when the sensor is in range, then [onWake] runs.
    func armPendingConnects(ids: [String]) {
        guard !foreground, let central, central.state == .poweredOn else { return }
        let uuids = ids.compactMap { UUID(uuidString: $0) }
        for peripheral in central.retrievePeripherals(withIdentifiers: uuids) {
            let id = peripheral.identifier.uuidString
            guard peripheral !== current, waiting[id] == nil else { continue }
            waiting[id] = peripheral
            central.connect(peripheral)
        }
    }

    /// The run did not take this pending connect, so it ends and the sensor is free again.
    func releaseWaiting(id: String) {
        guard let peripheral = waiting.removeValue(forKey: id), peripheral !== current else { return }
        central?.cancelPeripheralConnection(peripheral)
    }
}
