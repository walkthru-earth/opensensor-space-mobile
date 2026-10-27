import CoreBluetooth
import Foundation
import OpenSensorShared
import UIKit

/// BLE reader. Core Bluetooth owns pairing; no firmware-changing requests are sent. User actions run
/// in the foreground. Off screen only a sync or Debug request already running, and an Auto-sync step
/// for a known sensor (#23), keep a link. Each connection gets one shared [IosLink] once INFO named the
/// sensor. Sync, commands, Debug, Device settings and Files take leases of it, so a reply left over
/// from one run is drained before the next run's first request (#58). Core Bluetooth itself runs on
/// [BlePhysicalLink]'s serial queue (#80). This class keeps UI and coordination on the main actor,
/// names the connection by its peripheral id and a connection number, and hears [BleEvent]s in order.
@MainActor
final class BluetoothCompanion {
    let state: CompanionState
    private let wire: SyncWire
    private let credentials = LanCredentials()
    private let physical: BlePhysicalLink
    /// Numbers each connection. Events from an earlier connection are ignored.
    private var connection: UInt64 = 0
    /// Numbers each port, so a write from an earlier link fails on the Bluetooth queue.
    private var nextSender: UInt64 = 0
    /// The central manager's last reported state. Nil before its first report.
    private var centralState: CBManagerState?
    /// True when the GATT queue has nothing queued or in flight, as the Bluetooth queue last reported.
    private var gattIdle = true
    /// INFO showed the control and response characteristics, so the shared link can exist.
    private var canSync = false
    /// The write size INFO reported, plus the ATT header.
    private var mtu: Int32 = 0
    private var clockAttempted = false
    private var identity: SyncIdentity?
    private var tokenTimer: Timer?
    private var requestingToken = false
    /// The sensor whose last page closed while a sync, a Collect all or Auto-sync step, Wi-Fi access
    /// setup, a queued write or a lease held its link. Settling closes it once that work ended.
    /// Opening the sensor again keeps it.
    private var closeWhenIdle: String?
    /// Held while this side writes `GET_TOKEN` itself, so no Kotlin request shares the link.
    private var nativeLease: IosNativeLease?
    var onCredentialSaved: (() -> Void)?
    /// INFO named the sensor the user opened: peripheral id, advertised name, thing and hardware id.
    var onSensorOpened: ((String, String?, String, String) -> SensorOpenResult)?
    /// A Collect all step ended: the sensor id and whether every listed file is on this phone.
    var onCollectFinished: ((String, Bool) -> Void)?
    /// Set when access is already allowed, or when the user asks for it from My devices.
    /// Creating a central manager before that shows the Bluetooth permission prompt.
    private var hasCentral = false
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
    /// The connected or connecting peripheral's id.
    private var current: String?
    private var scanTimer: Timer?
    /// Covers the connect, then discovery and a first pairing. Nil once discovery settled.
    private var connectionTimer: Timer?
    /// How long Core Bluetooth may take to reach the sensor.
    private let connectTimeout: TimeInterval = 30
    /// How long discovery may take once connected. The first encrypted read shows the iOS passkey
    /// sheet, and the user reads six digits off the sensor, so this is far longer than the connect.
    private let discoveryTimeout: TimeInterval = 90
    private var freshnessTimer: Timer?
    /// Paced `live` and `status` reads while the MTU cannot notify a full snapshot (#159).
    private var snapshotTimer: Timer?
    private var foreground = true
    private let engine: SensorSync
    /// This connection's shared link, made once INFO named the sensor. RESPONSE notifications go to it.
    private var ioLink: IosLink?
    /// The command this side started, while it runs.
    private var commandHandle: SyncHandle?
    /// The shared collections (#81). Every Bluetooth sync runs on its controller for the device.
    var runs: SensorCollections?
    /// The collection this side started, from its start until its result arrived. The controller
    /// owns its retries, so it outlives a lost connection and reconnects through [dial].
    private var run: SyncHandle?
    /// Numbers [run], so a callback from an earlier run is ignored.
    private var runToken: UUID?
    /// The sensor of [run]. It outlives the connection while the controller waits to retry or dials
    /// again, so Disconnect and Forget reach the run then too.
    private var owned = OwnedRun()
    /// The controller asked for a link to the connecting sensor. Each answers once.
    private var dialWaiters: [(Bool) -> Void] = []
    /// Peripheral ids by hardware id, from INFO, so a retry reopens the same sensor.
    private var peripherals: [String: String] = [:]
    /// The saved peripheral for a hardware id the app has not opened since launch.
    var peripheralFor: ((String) -> String?)?
    /// The sensor an Auto-sync step is copying now (#23), and whether that step opened the link.
    private var backgroundId: String? {
        // My devices marks the link as Auto-sync's while a step owns it.
        didSet { if (oldValue == nil) != (backgroundId == nil) { state.bluetoothBackgroundOwned(owned: backgroundId != nil) } }
    }
    private var backgroundOpened = false
    private var backgroundDone: ((BackgroundStepResult) -> Void)?
    private var backgroundBytes: Int64 = 0
    private var backgroundTimer: Timer?
    /// A step asked for before the central manager reported its first state, as after a background launch.
    private var backgroundRequested: (id: String, done: (BackgroundStepResult) -> Void)?
    /// An armed pending connect completed. The shared run decides whether that sensor is due.
    var onWake: ((String) -> Void)?
    /// Core Bluetooth state restoration, so pending connects survive the app being ended by iOS.
    static let restoreId = "space.opensensor.app.central"

    /// Links keep working off screen only for work already under way.
    private var active: Bool {
        foreground || run != nil || commandHandle != nil || ioLink?.leased == true || backgroundId != nil
            || collectId != nil || !dialWaiters.isEmpty
    }

    /// A run, a command, a lease or Wi-Fi access setup holds the link.
    private var linkBusy: Bool { run != nil || commandHandle != nil || ioLink?.leased == true || requestingToken }

    private var poweredOn: Bool { centralState == .poweredOn }

    init(engine: SensorSync) {
        self.engine = engine
        let state = CompanionState()
        let wire = SyncWire()
        self.state = state
        self.wire = wire
        let uuids = BlePhysicalLink.Uuids(
            service: state.serviceUuid, info: state.infoUuid, live: state.liveUuid, status: state.statusUuid,
            control: wire.controlUuid, response: wire.responseUuid)
        let sink = EventSink()
        physical = BlePhysicalLink(uuids: uuids) { number, event in
            // The main queue is FIFO, so events keep the Bluetooth queue's order.
            DispatchQueue.main.async {
                MainActor.assumeIsolated { sink.companion?.handle(event, connection: number) }
            }
        }
        sink.companion = self
        let authorization = CBManager.authorization
        state.bluetoothAuthorizationChanged(raw: Int64(authorization.rawValue))
        // Already allowed, so creating the manager shows no prompt and My devices can scan at once.
        if authorization == .allowedAlways { requestAccess() }
    }

    /// Creates the central manager. The first time, iOS asks for Bluetooth access.
    func requestAccess() {
        guard !hasCentral else { return }
        hasCentral = true
        physical.start(restoreId: Self.restoreId)
    }

    /// The connected sensor's hardware id once INFO named it.
    var deviceId: String? { identity?.device }

    func scan() {
        guard foreground else { return }
        guard poweredOn else {
            // My devices scans only once access is allowed. Scanning waits for poweredOn.
            scanRequested = true
            if let centralState {
                powerChanged(centralState)
            } else {
                requestAccess()
            }
            return
        }
        scanRequested = false
        stopScan()
        state.scanStarted()
        physical.scan()
        scanTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.stopScan() }
        }
    }

    func stopScan() {
        scanRequested = false
        scanTimer?.invalidate()
        scanTimer = nil
        if poweredOn { physical.stopScan() }
        state.scanStopped()
    }

    /// Opens a sensor from My devices, nearby or known. Another open link closes first, because this
    /// app holds one Bluetooth link. Known sensors open through their saved identifier. The caller
    /// cancels Collect all first. A sync or Debug request the user started keeps its link, and the
    /// open is refused instead.
    func open(id: String) {
        guard foreground || collectOffScreen else { return }
        closeWhenIdle = nil
        if sameId(current, id) { return }
        if current != nil, run != nil || commandHandle != nil || ioLink?.leased == true, collectId == nil { return }
        guard poweredOn else {
            openRequested = id
            return
        }
        openRequested = nil
        if current != nil { disconnect() }
        connect(id)
    }

    /// One Collect all step over Bluetooth. Opens the sensor, syncs once it is ready, then closes it.
    /// A link the user holds stays untouched. A link to another sensor, or a sync or Debug request
    /// on this one, fails the step. An idle link to this sensor is synced and left open.
    func collect(id: String) {
        guard foreground || collectOffScreen, !linkBusy, collectId == nil, backgroundId == nil, poweredOn
        else {
            onCollectFinished?(id, false)
            return
        }
        if let current {
            guard current.caseInsensitiveCompare(id) == .orderedSame else {
                onCollectFinished?(id, false)
                return
            }
            collectOpened = false
        } else {
            open(id: id)
            guard sameId(current, id) else {
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
        guard let current, current.caseInsensitiveCompare(id) != .orderedSame,
            !linkBusy, collectId == nil, backgroundId == nil, gattIdle
        else { return }
        disconnect()
    }

    private func collectWhenReady() {
        guard collectId != nil else { return }
        // A step cancelled during the automatic clock command ends when that command hands back.
        if collectCancelled { return failCollect() }
        guard !linkBusy, gattIdle else { return }
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
        if let run { run.cancel() } else { failCollect() }
    }

    /// Ends the Collect all step that has not started its sync. A link the step opened closes, which
    /// reports the failure. A link the user held stays open.
    private func failCollect() {
        guard let id = collectId, run == nil else { return }
        collectTimer?.invalidate()
        collectTimer = nil
        if collectOpened {
            disconnect()
        } else {
            collectId = nil
            onCollectFinished?(id, false)
        }
    }

    /// Opens this sensor on the Bluetooth queue. A pending connect that already reached it is
    /// adopted there, and discovery starts as after a fresh connect.
    private func connect(_ id: String) {
        guard hasCentral else { return }
        stopScan()
        connection &+= 1
        current = id
        gattIdle = false
        state.connectionStarted(id: id)
        startConnectionTimer(connectTimeout)
        physical.connect(id: id, as: connection)
    }

    /// Replaces the connection timer. When it fires, the connect or discovery took too long.
    private func startConnectionTimer(_ seconds: TimeInterval) {
        connectionTimer?.invalidate()
        connectionTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.unexpectedLoss(notice: .connectTimedOut)
            }
        }
    }

    /// Ends the connection for a typed reason. The shared state picks the words. A collection this
    /// side started stops with it, because the app closed the link on purpose.
    func disconnect(notice: CompanionNotice = .disconnected) {
        run?.cancel()
        teardown()
        state.connectionClosedNoticed(notice: notice)
    }

    /// Ends the connection, which the sensor or the radio lost. A running collection stays with the
    /// shared controller, which retries through [dial] or ends with the typed cause.
    private func drop(notice: CompanionNotice) {
        teardown()
        state.connectionClosedNoticed(notice: notice)
    }

    private func unexpectedLoss(notice: CompanionNotice) {
        guard run != nil else {
            disconnect(notice: notice)
            return
        }
        // A lost bond ends the run, so the page shows the repair. Any other loss is dialled again.
        if notice == .pairingRemoved {
            drop(notice: notice)
        } else {
            teardown()
            state.bluetoothReconnecting(notice: notice)
        }
    }

    private func teardown() {
        openRequested = nil
        closeWhenIdle = nil
        // A command ends with its link. A collection sees the link end and retries or fails itself.
        commandHandle?.cancel()
        commandHandle = nil
        nativeLease?.end()
        nativeLease = nil
        ioLink?.close()
        ioLink = nil
        connectionTimer?.invalidate()
        freshnessTimer?.invalidate()
        snapshotTimer?.invalidate()
        connectionTimer = nil
        freshnessTimer = nil
        snapshotTimer = nil
        tokenTimer?.invalidate()
        tokenTimer = nil
        requestingToken = false
        identity = nil
        clockAttempted = false
        canSync = false
        mtu = 0
        state.wifiAccessReset()
        // Queued writes fail on the Bluetooth queue, and no port of this connection writes again.
        if current != nil {
            current = nil
            physical.disconnect(connection)
        }
        gattIdle = true
        // A command outcome held for its last write reaches Today now that the connection is gone.
        state.today.linkIdle()
        let waiters = dialWaiters
        dialWaiters = []
        waiters.forEach { $0(false) }
        // A step whose collection started hears its result instead.
        guard run == nil else { return }
        collectTimer?.invalidate()
        collectTimer = nil
        collectOpened = false
        if let id = collectId {
            // Timed out or refused before the sync started. Collect all moves on.
            collectId = nil
            onCollectFinished?(id, false)
        }
        if let done = backgroundDone {
            // The link ended before the step started its sync, and the run moves on.
            clearBackground()
            done(BackgroundStepResult.skipped("connect failed"))
        }
    }

    /// Ends any run of [device], also one waiting to retry or dialling again, and closes the link to
    /// this peripheral if it is open. Disconnect and Forget call it.
    func close(id: String, device: String?) {
        if sameId(openRequested, id) { openRequested = nil }
        if let device, owned.closes(device: device) { run?.cancel() }
        guard let current, current.caseInsensitiveCompare(id) == .orderedSame else { return }
        disconnect()
    }

    /// Copies every file this phone lacks from the connected sensor through the shared engine.
    /// UTC is synchronized before LIST, OPEN, READ and CLOSE through the operation queue.
    func sync(fileName: String? = nil) {
        // Today's Smart Sync and the Sync button say the link is busy instead of doing nothing.
        if !startSync(onlyName: fileName) { state.today.commandRefused() }
    }

    /// Starts the sync when the link is free. Collect all and Auto-sync steps call this from their
    /// idle waiters, so a refusal there stays quiet and the step waits for the next idle link. The
    /// shared controller for the device runs it over Bluetooth, takes its background time and
    /// retries a lost link through [dial].
    @discardableResult
    private func startSync(onlyName: String? = nil) -> Bool {
        // A lease still held by Debug or Device settings queues the sync behind it on the shared link.
        guard active, clockAttempted, current != nil, let link = ioLink, link.isOpen, let identity, run == nil,
            commandHandle == nil, !requestingToken
        else { return false }
        return beginRun(device: identity.device, onlyName: onlyName)
    }

    /// Starts a run iOS interrupted off screen, back on screen. With no open link to this sensor the
    /// shared controller dials it through [dial], as on a retry. False while other work holds the link.
    @discardableResult
    func resumeSync(device: String, onlyName: String?) -> Bool {
        guard foreground, run == nil, commandHandle == nil, ioLink?.leased != true, !requestingToken,
            collectId == nil, backgroundId == nil
        else { return false }
        return beginRun(device: device, onlyName: onlyName)
    }

    private func beginRun(device: String, onlyName: String?) -> Bool {
        guard let runs else { return false }
        // The controller ends a stalled sync itself, so the step timers are no longer needed.
        collectTimer?.invalidate()
        collectTimer = nil
        backgroundTimer?.invalidate()
        backgroundTimer = nil
        let token = UUID()
        runToken = token
        owned.started(device: device)
        state.bluetoothSyncStarted()
        let origin: RunOrigin = backgroundId != nil ? .autoSync : collectId != nil ? .collectAll : .user
        let handle = runs.collect(
            device: ModelThingId.BleAddress(address: device), onlyName: onlyName, prefer: .ble, origin: origin,
            progress: { [weak self] update in
                MainActor.assumeIsolated {
                    guard let self, self.runToken == token else { return }
                    self.state.bluetoothSyncProgressed(progress: update)
                    if self.backgroundId != nil { self.backgroundBytes = update.receivedBytes }
                }
            },
            finished: { [weak self] result in
                MainActor.assumeIsolated { self?.syncEnded(result, token: token) }
            })
        // The result may already have arrived for a refused start.
        if runToken == token { run = handle }
        return true
    }

    /// Sends automatic UTC synchronization or a confirmed flush through the shared session.
    func command(_ command: SensorCommand, automatic: Bool = false) {
        guard (foreground || automatic), current != nil, let link = ioLink, link.isOpen, run == nil,
            commandHandle == nil, !requestingToken
        else {
            state.today.commandRefused()
            return
        }
        if !automatic { state.today.commandStarted() }
        commandHandle = engine.command(link: link, command: command, automatic: automatic) { [weak self] result in
            MainActor.assumeIsolated { self?.commandEnded(result, link: link, automatic: automatic) }
        }
    }

    private func commandEnded(_ result: CommandResult, link: IosLink, automatic: Bool) {
        guard ioLink === link else { return }
        commandHandle = nil
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
        guard !linkBusy, gattIdle else { return }
        // A run that reconnected holds the link at once. Its clock was set before it started.
        if ioLink != nil, !clockAttempted, run == nil {
            clockAttempted = true
            command(.setTime, automatic: true)
            return
        }
        state.today.linkIdle()
        collectWhenReady()
        backgroundWhenReady()
        // A close the user asked for goes first, so a resume never delays it.
        if let id = closeWhenIdle {
            closeWhenIdle = nil
            closeIdle(id: id)
        }
        // A run iOS interrupted may have waited for this link to be free.
        runs?.retryInterrupted()
    }

    /// The collection's one result, after the controller handed the link back.
    private func syncEnded(_ result: SyncResult, token: UUID) {
        guard runToken == token else { return }
        runToken = nil
        run = nil
        owned.ended()
        let collected = collectId
        let opened = collectOpened
        collectId = nil
        collectOpened = false
        collectTimer?.invalidate()
        collectTimer = nil
        let background = backgroundDone
        let backgroundOwned = backgroundOpened
        let bytes = backgroundBytes
        clearBackground()
        if result.keepsLink, let link = ioLink, link.isOpen {
            state.bluetoothSyncFinished(result: result)
            // Collect all and Auto-sync close what they opened, so the next sensor can take the one
            // link. Off screen nothing stays open once its work ended.
            if (collected != nil && opened) || (background != nil && backgroundOwned) || !foreground {
                disconnect()
            } else {
                settle()
            }
        } else {
            // A stalled or failed link may still hold a write in flight. Start the next sync on a fresh connection.
            if current != nil { teardown() }
            state.bluetoothSyncClosed(result: result)
        }
        if let collected { onCollectFinished?(collected, result.end == .complete) }
        background?(BackgroundStepResult.ended(result, bytes: bytes))
    }

    /// The app left the screen. An idle link closes. A sync, a Debug request or an Auto-sync step
    /// keeps its link and closes it when it ends.
    func suspend() {
        foreground = false
        scanRequested = false
        stopScan()
        openRequested = nil
        guard run == nil, commandHandle == nil, ioLink?.leased != true, backgroundId == nil,
            !(collectOffScreen && collectId != nil)
        else { return }
        disconnect(notice: .resumeLive)
    }

    /// iOS launched the app without a screen, for a background task or a Bluetooth restoration.
    /// Nothing is open yet, so only the flag changes. The scene's resume sets it back.
    func launchedOffScreen() { foreground = false }

    /// Back on screen. Pending connects end, because the user drives the link again.
    func resume() {
        foreground = true
        physical.releaseWaiting(id: nil)
    }

    /// Closes the link to this sensor once its last Today, Device or Device settings page is gone
    /// (#16). A sync, Wi-Fi access setup, Collect all or an Auto-sync step, a command, a lease or a
    /// write still queued delays the close until that work ends and the link settles.
    func closeIdle(id: String) {
        guard let current, current.caseInsensitiveCompare(id) == .orderedSame else { return }
        guard run == nil, collectId == nil, backgroundId == nil, !requestingToken, !linkBusy, gattIdle else {
            closeWhenIdle = id
            return
        }
        disconnect()
    }

    /// True while a command, a Debug or Device settings lease, Wi-Fi access setup or a Collect all
    /// step holds the link to this sensor. A collection is the shared controller's to report.
    func isHeld(id: String) -> Bool {
        guard sameId(current, id) else { return false }
        return linkBusy || collectId != nil
    }

    /// Asks the sensor for its LAN token. The request takes a native lease of the shared link, so it
    /// waits for any Kotlin request and its reply stays on this side.
    func saveWifiAccess() {
        guard foreground, current != nil, identity != nil, let link = ioLink, run == nil, commandHandle == nil,
            !requestingToken, mtu >= 4
        else { return }
        requestingToken = true
        state.wifiAccessSaving()
        tokenTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.requestingToken else { return }
                // GET_TOKEN was written and only its reply is late, so only the request ends. A late
                // TOKEN then reaches the shared link, which drops a frame no request owns, and the
                // recorder redacts it. A write still in flight has wedged the GATT queue, so the
                // link closes as before.
                if self.gattIdle {
                    self.finishTokenRequest(.wifiAccessTimedOut)
                } else {
                    self.disconnect(notice: .wifiAccessTimedOut)
                }
            }
        }
        let number = connection
        link.leaseForNative { [weak self] lease in
            MainActor.assumeIsolated {
                guard let self, let lease, self.requestingToken, self.ioLink === link, self.connection == number else {
                    lease?.end()
                    // A sync took the link first, or it closed. The user can ask again.
                    if let self, self.requestingToken, self.ioLink === link { self.finishTokenRequest(.saveWifiAccess) }
                    return
                }
                self.nativeLease = lease
                self.gattIdle = false
                self.physical.requestToken(number)
            }
        }
    }

    private func finishTokenRequest(_ notice: CompanionNotice) {
        requestingToken = false
        tokenTimer?.invalidate()
        tokenTimer = nil
        nativeLease?.end()
        nativeLease = nil
        physical.endTokenRequest(connection)
        state.wifiAccessNoticed(available: true, busy: false, notice: notice)
        settle()
    }
}

/// Holds the companion weakly for the physical link's event closure, which is made before `self` is.
@MainActor
private final class EventSink: Sendable {
    weak var companion: BluetoothCompanion?
}

/// Events from the Bluetooth queue, in arrival order on the main actor.
extension BluetoothCompanion {
    fileprivate func handle(_ event: BleEvent, connection number: UInt64) {
        switch event {
        case .power(let state): return powerChanged(state)
        case .found(let id, let name, let rssi):
            guard foreground else { return }
            state.deviceFound(id: id, name: name, rssi: rssi)
            return
        case .wake(let id, let restored):
            // An armed pending connect completed. On screen the user drives the link, so it ends.
            // Off screen it stays until the run takes it or releases it. A restored one waits for the run.
            if restored || !foreground {
                onWake?(id)
            } else {
                releaseWaiting(id: id)
            }
            return
        default: break
        }
        // A late event from a connection that already ended answers nothing now.
        guard number == connection, current != nil else { return }
        switch event {
        case .connected:
            if !active { return disconnect() }
            // The connect timer stops here. Discovery, with a first pairing sheet, gets its own.
            if connectionTimer != nil { startConnectionTimer(discoveryTimeout) }
        case .info(let json, let name, let mtu, let canSync):
            sensorOpened(json: json, name: name, mtu: mtu, canSync: canSync)
        case .ready:
            connectionTimer?.invalidate()
            connectionTimer = nil
            startSnapshotReads()
            settle()
        case .live(let json):
            guard active else { return }
            state.receiveLive(json: json)
            freshnessTimer?.invalidate()
            freshnessTimer = Timer.scheduledTimer(withTimeInterval: 35, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated { self?.state.readingExpired() }
            }
        case .status(let json):
            guard active else { return }
            state.receiveStatus(json: json)
        case .gatt(let idle):
            gattIdle = idle
            if idle { settle() }
        case .token(let kind, let payload, let port):
            tokenReplied(kind: kind, payload: payload, port: port)
        case .lost(let loss):
            // The Bluetooth queue already ended the connection.
            switch loss {
            case .unknownSensor, .connectFailed: unexpectedLoss(notice: .connectFailed)
            case .disconnected: unexpectedLoss(notice: .sensorDisconnected)
            case .serviceMissing: disconnect(notice: .serviceMissing)
            case .characteristicsMissing: disconnect(notice: .characteristicsMissing)
            case .notifyFailed: disconnect(notice: .notifyFailed)
            case .readFailed: disconnect(notice: .readFailed)
            case .tokenFailed: disconnect(notice: .tokenFailed)
            case .pairingRemoved: pairingRemoved()
            }
        case .power, .found, .wake: break
        }
    }

    /// The sensor refused this phone's pairing keys. Nothing retries, because only removing the
    /// pairing in iOS Settings and pairing again fixes it. An Auto-sync step is skipped, not lost.
    private func pairingRemoved() {
        if run == nil, let done = backgroundDone {
            clearBackground()
            done(BackgroundStepResult.skipped("pairing removed"))
        }
        // A collection already heard the missing bond from its link and ends without a retry.
        unexpectedLoss(notice: .pairingRemoved)
    }

    fileprivate func powerChanged(_ newState: CBManagerState) {
        centralState = newState
        let notice: CompanionNotice?
        switch newState {
        case .poweredOn: notice = nil
        case .poweredOff: notice = .bluetoothOff
        case .unauthorized: notice = .bluetoothDenied
        case .unsupported: notice = .bluetoothUnsupported
        default: notice = .bluetoothStarting
        }
        switch newState {
        case .poweredOn, .unknown, .resetting: break
        default:
            scanRequested = false
            openRequested = nil
        }
        if newState != .poweredOn, let notice {
            // A stack reset is brief and not a power off. iOS drops the connection anyway, so the
            // sensor open on screen is asked for again and opens once the radio is back on.
            let reopen = newState == .resetting && foreground && collectId == nil && backgroundId == nil
                ? current ?? openRequested : nil
            let rescan = newState == .resetting && foreground && (scanRequested || scanTimer != nil)
            stopScan()
            // A collection heard the radio go from its link. The controller retries or ends it.
            unexpectedLoss(notice: notice)
            if let reopen { openRequested = reopen }
            if rescan { scanRequested = true }
        }
        // The shared policy maps this to its status and offers Settings only after a denial.
        state.bluetoothAuthorizationChanged(raw: Int64(CBManager.authorization.rawValue))
        state.bluetoothNoticed(available: newState == .poweredOn, notice: notice)
        state.bluetoothPowerChanged(poweredOff: newState == .poweredOff)
        if newState == .poweredOn, let id = openRequested { open(id: id) }
        if newState == .poweredOn, scanRequested { scan() }
        if let request = backgroundRequested, newState != .unknown, newState != .resetting {
            backgroundRequested = nil
            backgroundSync(id: request.id, done: request.done)
        }
    }

    private func sensorOpened(json: String, name: String?, mtu: Int32, canSync: Bool) {
        guard let id = current, let observed = wire.identity(json: json) else {
            disconnect(notice: .infoInvalid)
            return
        }
        guard let result = onSensorOpened?(id, name, observed.thing, observed.device) else {
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
        peripherals[observed.device] = id
        self.mtu = mtu
        self.canSync = canSync
        openLink(identity: observed)
        state.wifiAccessNoticed(available: canSync, busy: false, notice: .saveWifiAccess)
        state.bluetoothSyncAvailable(available: canSync)
    }

    /// The firmware omits a snapshot notification larger than MTU - 3, so below a full snapshot MTU
    /// this side reads both values every interval. A tick skips while a run, command, lease or
    /// queued GATT operation holds the link, and the timer ends with the connection.
    private func startSnapshotReads() {
        snapshotTimer?.invalidate()
        snapshotTimer = nil
        guard BleSnapshotReads.needed(mtu: Int(mtu)) else { return }
        let number = connection
        snapshotTimer = Timer.scheduledTimer(withTimeInterval: BleSnapshotReads.interval, repeats: true) {
            [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.connection == number, self.current != nil, self.active, !self.linkBusy,
                    self.gattIdle
                else { return }
                self.physical.readSnapshots(number)
            }
        }
    }

    private func tokenReplied(kind: String, payload: Data, port: Int) {
        guard nativeLease != nil, requestingToken else { return }
        guard kind == "token", let identity, let id = current else {
            finishTokenRequest(.tokenRefused)
            return
        }
        do {
            try credentials.save(
                LanCredential(thing: identity.thing, device: identity.device, peripheral: id, token: payload, port: port))
            finishTokenRequest(.tokenStored)
            onCredentialSaved?()
        } catch { finishTokenRequest(LanFailure.credentialsUnavailable.notice) }
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

    /// Makes this connection's shared link once INFO named the sensor. The Bluetooth queue hears of
    /// it before Kotlin can write, and subscribes to RESPONSE before the first write.
    fileprivate func openLink(identity: SyncIdentity) {
        guard ioLink == nil, canSync else { return }
        nextSender &+= 1
        let port = BlePort(physical: physical, sender: nextSender, mtu: mtu)
        let link = IosLink.make(port: port, overWifi: false, identity: identity)
        physical.attach(link, sender: port.sender, to: connection)
        // The listener holds neither the link nor this companion, so a replaced link is freed.
        whenLeasesEnd(
            of: link, isCurrent: { [weak self] id in self?.ioLink.map { ObjectIdentifier($0) } == id },
            released: { [weak self] in self?.leaseEnded() })
        let id = ObjectIdentifier(link)
        link.whenClosed { @Sendable [weak self] in
            Task { @MainActor in self?.linkClosed(id) }
        }
        ioLink = link
        let waiters = dialWaiters
        dialWaiters = []
        waiters.forEach { $0(true) }
    }

    /// A lease ended. Work waiting for an idle link runs, and off screen an idle link closes.
    private func leaseEnded() {
        settle()
        if !foreground, current != nil, !linkBusy, backgroundId == nil, collectId == nil { disconnect() }
    }

    /// The shared link closed or was poisoned while the connection is still up. A running sync ends
    /// with it and closes the connection itself. Otherwise the connection closes now.
    private func linkClosed(_ id: ObjectIdentifier) {
        guard let ioLink, ObjectIdentifier(ioLink) == id else { return }
        // A collection on it retries on a fresh connection, so this one closes either way.
        unexpectedLoss(notice: .sensorDisconnected)
    }

    /// The open link to this hardware id for the shared controller, also off screen while work holds it.
    func syncLink(device: String) -> IosLink? {
        guard active, current != nil, let identity, identity.device == device, let ioLink, ioLink.isOpen else {
            return nil
        }
        return ioLink
    }

    /// Why no Bluetooth dial can open a link now, or nil when one may. Bluetooth never started for
    /// this app reads as not allowed. A radio that is still starting is a plain refusal, retried later.
    var dialUnavailable: DialResult? {
        guard hasCentral else { return .permissionMissing }
        switch centralState {
        case .poweredOff: return .radioOff
        case .unauthorized: return .permissionMissing
        case .unsupported: return .noAdapter
        default: return nil
        }
    }

    /// The shared controller found no open link for this sensor's collection and asks for one.
    /// [done] runs once, true when the link is open and identified. A link held for other work
    /// on another sensor refuses.
    func dial(device: String, done: @escaping (Bool) -> Void) {
        guard hasCentral, poweredOn, let id = peripherals[device] ?? peripheralFor?(device), UUID(uuidString: id) != nil
        else { return done(false) }
        if sameId(current, id) {
            if let identity, identity.device == device, ioLink?.isOpen == true { return done(true) }
            dialWaiters.append(done)
            return
        }
        if current != nil {
            guard commandHandle == nil, ioLink?.leased != true, !requestingToken, collectId == nil, backgroundId == nil
            else { return done(false) }
            drop(notice: .disconnected)
        }
        dialWaiters.append(done)
        connect(id)
    }
}

/// Auto-sync over Bluetooth (#23). Only sensors this phone already read over an encrypted link are
/// opened, by their saved peripheral identifier. iOS shows no pairing prompt off screen, so a sensor
/// whose bond is gone fails the step instead of asking.
extension BluetoothCompanion {
    /// One step of the shared background run. Adopts an idle link to this sensor, or a pending connect
    /// that completed, or opens the saved peripheral. Answers once, with no ids or secrets in the result.
    func backgroundSync(id: String, done: @escaping (BackgroundStepResult) -> Void) {
        guard backgroundDone == nil, backgroundRequested == nil, collectId == nil, run == nil else {
            done(BackgroundStepResult.skipped("busy"))
            return
        }
        guard hasCentral else {
            done(BackgroundStepResult.skipped("bluetooth not allowed"))
            return
        }
        let reported = centralState ?? .unknown
        if reported == .unknown || reported == .resetting {
            backgroundRequested = (id, done)
            return
        }
        guard reported == .poweredOn else {
            done(BackgroundStepResult.skipped("bluetooth off"))
            return
        }
        if let current {
            // The one link is in use for another sensor, or for work the user started.
            guard current.caseInsensitiveCompare(id) == .orderedSame, !linkBusy else {
                done(BackgroundStepResult.skipped("in use"))
                return
            }
            backgroundOpened = false
        } else {
            guard UUID(uuidString: id) != nil else {
                done(BackgroundStepResult.skipped("unknown sensor"))
                return
            }
            backgroundOpened = true
            connect(id)
        }
        backgroundId = id
        backgroundDone = done
        backgroundBytes = 0
        backgroundTimer = Timer.scheduledTimer(withTimeInterval: 90, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.failBackground() }
        }
        backgroundWhenReady()
    }

    fileprivate func backgroundWhenReady() {
        guard backgroundId != nil, !linkBusy, gattIdle else { return }
        if identity == nil || ioLink == nil {
            if connectionTimer == nil, current != nil { failBackground() }
            return
        }
        startSync()
    }

    /// Ends a step that never started its sync. A link the step opened closes, which reports it.
    private func failBackground() {
        guard let done = backgroundDone, run == nil else { return }
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
        guard !foreground, poweredOn else { return }
        physical.arm(ids: ids.filter { !sameId(current, $0) })
    }

    /// The run did not take this pending connect, so it ends and the sensor is free again.
    func releaseWaiting(id: String) {
        guard !sameId(current, id) else { return }
        physical.releaseWaiting(id: id)
    }
}

/// Peripheral ids are UUID strings, and the shared code may carry them in another case.
private func sameId(_ a: String?, _ b: String) -> Bool {
    a?.caseInsensitiveCompare(b) == .orderedSame
}
