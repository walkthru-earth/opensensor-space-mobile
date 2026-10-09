import CoreBluetooth
import Foundation
import OpenSensorShared

/// BLE reader. Core Bluetooth owns pairing; no firmware-changing requests are sent. User actions run
/// in the foreground. Off screen only a sync or Debug request already running, and an Auto-sync step
/// for a known sensor (#23), keep a link.
@MainActor
final class BluetoothCompanion: NSObject, @preconcurrency CBCentralManagerDelegate, @preconcurrency CBPeripheralDelegate
{
    let state = CompanionState()
    private let wire = SyncWire()
    private let credentials = LanCredentials()
    private var control: CBCharacteristic?
    private var response: CBCharacteristic?
    private var identity: SyncIdentity?
    private var tokenTimer: Timer?
    private var requestingToken = false
    var onCredentialSaved: (() -> Void)?
    /// INFO named the sensor the user opened: peripheral id, advertised name, station and hardware id.
    var onSensorOpened: ((String, String?, String, String) -> Void)?
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
    /// Whether this Collect all step opened the link, so it closes only what it opened.
    private var collectOpened = false
    /// Ends a Collect all step that never started its sync, for example a Debug link never handed back.
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
    /// The shared engine's link while a Bluetooth sync runs. RESPONSE notifications go to it.
    private var link: SensorLink?
    private var syncHandle: SyncHandle?
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
    private var active: Bool { foreground || link != nil || backgroundId != nil }

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
        case write(CBCharacteristic, Data)
        var characteristic: CBCharacteristic {
            switch self {
            case .read(let characteristic), .subscribe(let characteristic), .write(let characteristic, _):
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
        guard foreground else { return }
        if let current, current.identifier.uuidString == id { return }
        if current != nil, link != nil, collectId == nil { return }
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
        guard foreground, link == nil, collectId == nil, backgroundId == nil, let central, central.state == .poweredOn
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
        collectTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.failCollect() }
        }
        collectWhenReady()
    }

    private func collectWhenReady() {
        guard collectId != nil, link == nil, pending == nil, operations.isEmpty, !requestingToken else { return }
        // Discovery has settled once the connect timer is gone. A sensor without the sync characteristics
        // or a readable identity cannot be collected, so the step ends instead of waiting.
        if identity == nil || control == nil || response == nil {
            if connectionTimer == nil, current != nil { failCollect() }
            return
        }
        startSync()
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
                self?.disconnect(message: "Connection timed out. Keep the sensor nearby and try again.")
            }
        }
        central.connect(peripheral)
    }

    func disconnect(message: String = "Disconnected. Your sensor keeps recording.") {
        teardown()
        state.connectionClosed(explanation: message)
    }

    /// Ends the connection for a typed reason, shown in Android's words.
    func disconnect(notice: CompanionNotice) {
        teardown()
        state.connectionClosedNoticed(notice: notice)
    }

    private func teardown() {
        openRequested = nil
        // The engine sees the link end at once. Its result for this link is then ignored.
        syncHandle?.cancel()
        syncHandle = nil
        link?.linkClosed()
        link = nil
        responseSubscribed = false
        connectionTimer?.invalidate()
        freshnessTimer?.invalidate()
        connectionTimer = nil
        freshnessTimer = nil
        tokenTimer?.invalidate()
        tokenTimer = nil
        requestingToken = false
        identity = nil
        control = nil
        response = nil
        state.wifiAccessChanged(available: false, busy: false, explanation: "")
        operations.removeAll()
        pending = nil
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
        if openRequested == id { openRequested = nil }
        guard let current, current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame else { return }
        disconnect()
    }

    /// Copies every file this phone lacks from the connected sensor through the shared engine.
    /// Requests are LIST, OPEN, READ and CLOSE only, written in order through the operation queue.
    func sync() {
        // Today's Smart Sync and the Sync button say the link is busy instead of doing nothing.
        if !startSync() { state.today.commandRefused() }
    }

    /// Starts the sync when the link is free. Collect all and Auto-sync steps call this from their
    /// idle waiters, so a refusal there stays quiet and the step waits for the next idle link.
    @discardableResult
    private func startSync() -> Bool {
        guard active, let peripheral = current, control != nil, let response, let identity, link == nil,
            pending == nil, operations.isEmpty, !requestingToken
        else { return false }
        let mtu = Int32(peripheral.maximumWriteValueLength(for: .withoutResponse) + 3)
        let link = SensorLink(overWifi: false, address: identity.device, mtu: mtu) { [weak self] body in
            // Kotlin sends on the main thread, in request order.
            MainActor.assumeIsolated { self?.write(body) }
        }
        self.link = link
        if !responseSubscribed { operations.append(.subscribe(response)) }
        // The engine ends a stalled sync itself, so the step timers are no longer needed.
        collectTimer?.invalidate()
        collectTimer = nil
        backgroundTimer?.invalidate()
        backgroundTimer = nil
        state.bluetoothSyncStarted()
        syncHandle = engine.start(
            link: link, station: identity.station, device: identity.device, maxRead: identity.maxRead,
            progress: { [weak self] update in
                MainActor.assumeIsolated {
                    guard let self, self.link === link else { return }
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
        performNext()
        return true
    }

    /// Sends one confirmed SET_TIME or FLUSH from Today through the shared session. Today asked
    /// first. A busy link refuses it, and the outcome always reaches Today.
    func command(_ command: SensorCommand) {
        guard foreground, let peripheral = current, control != nil, let response, let identity, link == nil,
            pending == nil, operations.isEmpty, !requestingToken
        else {
            state.today.commandRefused()
            return
        }
        let mtu = Int32(peripheral.maximumWriteValueLength(for: .withoutResponse) + 3)
        let link = SensorLink(overWifi: false, address: identity.device, mtu: mtu) { [weak self] body in
            MainActor.assumeIsolated { self?.write(body) }
        }
        self.link = link
        if !responseSubscribed { operations.append(.subscribe(response)) }
        state.today.commandStarted()
        syncHandle = engine.command(link: link, command: command) { [weak self] result in
            MainActor.assumeIsolated { self?.commandEnded(result, link: link) }
        }
        performNext()
    }

    private func commandEnded(_ result: CommandResult, link: SensorLink) {
        if self.link === link {
            self.link = nil
            syncHandle = nil
        }
        // The command may have queued a last write, the status request after SET_TIME. Today hears the
        // outcome, and Smart Sync starts its file sync, once that write completes.
        state.today.commandEnded(result: result)
        settle()
    }

    /// Runs what waits for an idle link once no write or read is queued or in flight. First the
    /// outcome of a command for Today, then a Collect all step or an Auto-sync step waiting for the link.
    private func settle() {
        guard link == nil, pending == nil, operations.isEmpty else { return }
        state.today.linkIdle()
        collectWhenReady()
        backgroundWhenReady()
    }

    private func write(_ body: Data) {
        guard let control, link != nil, current != nil else { return }
        operations.append(.write(control, body))
        performNext()
    }

    private func syncEnded(_ result: SyncResult, link: SensorLink) {
        guard self.link === link else { return }
        self.link = nil
        syncHandle = nil
        let collected = collectId
        let opened = collectOpened
        collectId = nil
        collectOpened = false
        let background = backgroundDone
        let backgroundOwned = backgroundOpened
        let bytes = backgroundBytes
        clearBackground()
        if result.keepsLink {
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
        scanRequested = false
        stopScan()
        openRequested = nil
        guard link == nil, backgroundId == nil else { return }
        disconnect(message: "Find your sensor again to resume live readings.")
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

    /// Closes an idle link to this sensor once its page is gone (#16). A sync, a Debug request,
    /// Wi-Fi access setup, Collect all or an Auto-sync step keeps it.
    func closeIdle(id: String) {
        guard let current, current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame, link == nil,
            collectId == nil, backgroundId == nil, !requestingToken
        else { return }
        disconnect()
    }

    /// True while the user's work holds the link to this sensor. An idle open link is not held.
    func isBusy(id: String) -> Bool {
        guard let current, current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame else {
            return false
        }
        return link != nil || requestingToken || collectId != nil
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let message: String
        switch central.state {
        case .poweredOn: message = ""
        case .poweredOff: message = "Turn on Bluetooth to find your sensors."
        case .unauthorized: message = "Allow Bluetooth for opensensor.space in Settings."
        case .unsupported: message = "Bluetooth is not available on this device."
        default: message = "Bluetooth is getting ready…"
        }
        switch central.state {
        case .poweredOn, .unknown, .resetting: break
        default:
            scanRequested = false
            openRequested = nil
        }
        if central.state != .poweredOn {
            stopScan()
            disconnect(message: message)
        }
        switch central.state {
        case .poweredOn, .unknown, .resetting: break
        default: waiting.removeAll()
        }
        // The shared policy maps this to its status and offers Settings only after a denial.
        state.bluetoothAuthorizationChanged(raw: Int64(CBManager.authorization.rawValue))
        state.bluetoothChanged(available: central.state == .poweredOn, explanation: message)
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
        disconnect(message: "Could not connect. Check that the sensor is nearby and available for pairing.")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if current !== peripheral { waiting[peripheral.identifier.uuidString] = nil }
        guard current === peripheral else { return }
        disconnect(message: "The sensor disconnected. Find it again to reconnect.")
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard current === peripheral else { return }
        guard error == nil,
            let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: state.serviceUuid) })
        else {
            disconnect(message: "The AQ Sync service could not be opened.")
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
            disconnect(message: "This sensor is missing the required live-reading characteristics.")
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

    func saveWifiAccess() {
        guard foreground, let peripheral = current, let control, let response, identity != nil, link == nil,
            pending == nil, operations.isEmpty, !requestingToken,
            peripheral.maximumWriteValueLength(for: .withResponse) >= 1
        else { return }
        requestingToken = true
        state.wifiAccessChanged(available: true, busy: true, explanation: "Saving Wi-Fi access…")
        operations = [.subscribe(response), .write(control, wire.tokenRequest())]
        tokenTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.disconnect(message: "Wi-Fi access timed out. Reconnect over Bluetooth and try again.")
            }
        }
        performNext()
    }

    private func finishTokenRequest(_ notice: CompanionNotice) {
        requestingToken = false
        tokenTimer?.invalidate()
        tokenTimer = nil
        state.wifiAccessNoticed(available: true, busy: false, notice: notice)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard current === peripheral, case .write(let expected, _) = pending, expected === characteristic else {
            return
        }
        if error != nil, let link {
            // The engine ends this sync as a lost link, then the connection closes.
            operations.removeAll()
            pending = nil
            link.linkClosed()
            return
        }
        if error != nil {
            disconnect(notice: .tokenFailed)
            return
        }
        pending = nil
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
        case .write(let characteristic, let data): peripheral.writeValue(data, for: characteristic, type: .withResponse)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?
    ) {
        guard current === peripheral else { return }
        guard error == nil, characteristic.isNotifying else {
            disconnect(message: "Notifications could not be enabled. Check the pairing PIN and try again.")
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
            if let link {
                if error == nil, let data = characteristic.value { link.deliver(frame: data) }
                return
            }
            guard requestingToken, error == nil, let data = characteristic.value,
                let reply = wire.decode(data: data)
            else { return }
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
            disconnect(message: "The sensor could not be read. Check the pairing PIN and try again.")
            return
        }
        switch characteristic.uuid {
        case CBUUID(string: state.infoUuid):
            guard state.receiveInfo(json: json) else {
                disconnect(notice: .infoInvalid)
                return
            }
            identity = wire.identity(json: json)
            state.wifiAccessNoticed(
                available: identity != nil && control != nil && response != nil, busy: false,
                notice: .saveWifiAccess)
            state.bluetoothSyncAvailable(available: identity != nil && control != nil && response != nil)
            if let identity {
                let id = peripheral.identifier.uuidString
                onSensorOpened?(id, advertisedNames[id] ?? peripheral.name, identity.station, identity.device)
            }
        case CBUUID(string: state.liveUuid):
            state.receiveLive(json: json)
            freshnessTimer?.invalidate()
            freshnessTimer = Timer.scheduledTimer(withTimeInterval: 35, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated { self?.state.readingExpired() }
            }
        case CBUUID(string: state.statusUuid): state.receiveStatus(json: json)
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
    /// Lends the shared Debug screen a link to the connected sensor for one request, under the same
    /// rules as a sync. Nil when another sensor is connected, or a sync, a request or Wi-Fi access
    /// setup holds the link. The link is handed back when Kotlin closes it, or ends with the connection.
    func openDebugLink(device: String) -> SensorLink? {
        guard foreground, let peripheral = current, control != nil, let response, let identity,
            identity.device == device, link == nil, pending == nil, operations.isEmpty, !requestingToken
        else { return nil }
        let mtu = Int32(peripheral.maximumWriteValueLength(for: .withoutResponse) + 3)
        let link = SensorLink(overWifi: false, address: identity.device, mtu: mtu) { [weak self] body in
            // Kotlin sends on the main thread, in request order.
            MainActor.assumeIsolated { self?.write(body) }
        }
        self.link = link
        link.whenClosed { [weak self] in
            // A Collect all step waiting for the link starts once Debug hands it back.
            let release = {
                guard let self, self.link === link else { return }
                self.link = nil
                self.collectWhenReady()
                self.backgroundWhenReady()
                // Off screen the lent link closes once Debug hands it back.
                if !self.foreground, self.backgroundId == nil, self.collectId == nil { self.disconnect() }
            }
            if Thread.isMainThread { MainActor.assumeIsolated { release() } } else { Task { @MainActor in release() } }
        }
        if !responseSubscribed {
            operations.append(.subscribe(response))
            performNext()
        }
        return link
    }
}

/// Auto-sync over Bluetooth (#23). Only sensors this phone already read over an encrypted link are
/// opened, by their saved peripheral identifier. iOS shows no pairing prompt off screen, so a sensor
/// whose bond is gone fails the step instead of asking.
extension BluetoothCompanion {
    /// One step of the shared background run. Adopts an idle link to this sensor, or a pending connect
    /// that completed, or opens the saved peripheral. Answers once, with no ids or secrets in the result.
    func backgroundSync(id: String, done: @escaping (BackgroundStepResult) -> Void) {
        guard backgroundDone == nil, backgroundRequested == nil, collectId == nil else {
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
            guard current.identifier.uuidString.caseInsensitiveCompare(id) == .orderedSame, link == nil,
                !requestingToken
            else {
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
        guard backgroundId != nil, link == nil, pending == nil, operations.isEmpty, !requestingToken else { return }
        if identity == nil || control == nil || response == nil {
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
