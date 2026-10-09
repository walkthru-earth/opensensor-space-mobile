import CoreBluetooth
import Foundation
import OpenSensorShared

/// Foreground-only BLE reader. Core Bluetooth owns pairing; no firmware-changing requests are sent.
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
    /// Created on the first user scan. Creating a central manager shows the Bluetooth permission prompt.
    private var central: CBCentralManager?
    /// A scan the user asked for before the central manager reported its first state.
    private var scanRequested = false
    private var discovered: [String: CBPeripheral] = [:]
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

    init(engine: SensorSync) {
        self.engine = engine
        super.init()
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
        guard foreground, current == nil else { return }
        guard let central, central.state == .poweredOn else {
            // The first tap creates the manager. Scanning waits for poweredOn.
            scanRequested = true
            if let existing = self.central {
                centralManagerDidUpdateState(existing)
            } else {
                state.bluetoothChanged(available: false, explanation: "Bluetooth is getting ready…")
                self.central = CBCentralManager(delegate: self, queue: .main)
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

    private func stopScan() {
        scanTimer?.invalidate()
        scanTimer = nil
        if let central, central.state == .poweredOn { central.stopScan() }
        state.scanStopped()
    }

    func connect(id: String) {
        guard foreground, let central, central.state == .poweredOn, current == nil, let peripheral = discovered[id] else {
            return
        }
        stopScan()
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
        if let peripheral = current {
            current = nil
            peripheral.delegate = nil
            if let central, central.state == .poweredOn { central.cancelPeripheralConnection(peripheral) }
        }
    }

    /// Copies every file this phone lacks from the connected sensor through the shared engine.
    /// Requests are LIST, OPEN, READ and CLOSE only, written in order through the operation queue.
    func sync() {
        guard foreground, let peripheral = current, control != nil, let response, let identity, link == nil,
            pending == nil, operations.isEmpty, !requestingToken
        else { return }
        let mtu = Int32(peripheral.maximumWriteValueLength(for: .withoutResponse) + 3)
        let link = SensorLink(overWifi: false, address: identity.device, mtu: mtu) { [weak self] body in
            // Kotlin sends on the main thread, in request order.
            MainActor.assumeIsolated { self?.write(body) }
        }
        self.link = link
        if !responseSubscribed { operations.append(.subscribe(response)) }
        state.bluetoothSyncStarted()
        syncHandle = engine.start(
            link: link, station: identity.station, device: identity.device, maxRead: identity.maxRead,
            progress: { [weak self] update in
                MainActor.assumeIsolated {
                    guard let self, self.link === link else { return }
                    self.state.bluetoothSyncProgressed(progress: update)
                }
            },
            finished: { [weak self] result in
                MainActor.assumeIsolated { self?.syncEnded(result, link: link) }
            })
        performNext()
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
        if result.keepsLink {
            state.bluetoothSyncFinished(result: result)
            return
        }
        // A stalled or failed link may still hold a write in flight. Start the next sync on a fresh connection.
        teardown()
        state.bluetoothSyncClosed(result: result)
    }

    func suspend() {
        foreground = false
        scanRequested = false
        stopScan()
        disconnect(message: "Find your sensor again to resume live readings.")
    }

    func resume() { foreground = true }

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
        default: scanRequested = false
        }
        if central.state != .poweredOn {
            stopScan()
            disconnect(message: message)
        }
        // The shared policy maps this to its status and offers Settings only after a denial.
        state.bluetoothAuthorizationChanged(raw: Int64(CBManager.authorization.rawValue))
        state.bluetoothChanged(available: central.state == .poweredOn, explanation: message)
        if central.state == .poweredOn, scanRequested { scan() }
    }

    func centralManager(
        _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any], rssi RSSI: NSNumber
    ) {
        guard foreground, current == nil else { return }
        let id = peripheral.identifier.uuidString
        discovered[id] = peripheral
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? "AQ sensor"
        state.deviceFound(id: id, name: name, rssi: RSSI.int32Value)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard current === peripheral, foreground else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverServices([CBUUID(string: state.serviceUuid)])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard current === peripheral else { return }
        disconnect(message: "Could not connect. Check that the sensor is nearby and available for pairing.")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
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
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard current === peripheral, foreground else { return }
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
        }
    }
}
