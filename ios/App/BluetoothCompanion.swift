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
    private var central: CBCentralManager!
    private var discovered: [String: CBPeripheral] = [:]
    private var current: CBPeripheral?
    private var scanTimer: Timer?
    private var connectionTimer: Timer?
    private var freshnessTimer: Timer?
    private var operations: [Operation] = []
    private var pending: Operation?
    private var foreground = true

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

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func scan() {
        guard foreground, central.state == .poweredOn, current == nil else { return }
        stopScan()
        discovered.removeAll()
        state.scanStarted()
        central.scanForPeripherals(withServices: [CBUUID(string: state.serviceUuid)])
        scanTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.stopScan() }
        }
    }

    private func stopScan() {
        scanTimer?.invalidate()
        scanTimer = nil
        if central.state == .poweredOn { central.stopScan() }
        state.scanStopped()
    }

    func connect(id: String) {
        guard foreground, central.state == .poweredOn, current == nil, let peripheral = discovered[id] else { return }
        stopScan()
        current = peripheral
        peripheral.delegate = self
        state.connectionStarted(id: id)
        connectionTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.disconnect(message: "Connection timed out. Keep the sensor nearby and try again.")
            }
        }
        central.connect(peripheral)
    }

    func disconnect(message: String = "Disconnected. Your sensor keeps recording.") {
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
            if central.state == .poweredOn { central.cancelPeripheralConnection(peripheral) }
        }
        state.connectionClosed(explanation: message)
    }

    func suspend() {
        foreground = false
        stopScan()
        disconnect(message: "Find your sensor again to resume live readings.")
    }

    func resume() { foreground = true }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let message: String
        switch central.state {
        case .poweredOn: message = "Find a nearby sensor to see its live readings."
        case .poweredOff: message = "Turn on Bluetooth to find your sensors."
        case .unauthorized: message = "Allow Bluetooth access for opensensor.space in Settings."
        case .unsupported: message = "Bluetooth is unavailable on this device. Use an iPhone to connect to sensors."
        default: message = "Bluetooth is getting ready…"
        }
        if central.state != .poweredOn {
            stopScan()
            disconnect(message: message)
        }
        state.bluetoothChanged(available: central.state == .poweredOn, explanation: message)
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
        guard foreground, let peripheral = current, let control, let response, identity != nil,
            pending == nil, operations.isEmpty, !requestingToken,
            peripheral.maximumWriteValueLength(for: .withResponse) >= 1
        else { return }
        requestingToken = true
        state.wifiAccessChanged(available: true, busy: true, explanation: "Saving Wi-Fi access…")
        operations = [.subscribe(response), .write(control, Data(wireHex: wire.tokenRequest())!)]
        tokenTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.disconnect(message: "Wi-Fi access timed out. Reconnect over Bluetooth and try again.")
            }
        }
        performNext()
    }

    private func finishTokenRequest(_ explanation: String) {
        requestingToken = false
        tokenTimer?.invalidate()
        tokenTimer = nil
        state.wifiAccessChanged(available: true, busy: false, explanation: explanation)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard current === peripheral, case .write(let expected, _) = pending, expected === characteristic else {
            return
        }
        if error != nil {
            disconnect(message: "Wi-Fi access could not be requested. Check pairing and try again.")
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
        if case .subscribe(let expected) = pending, expected === characteristic {
            pending = nil
            performNext()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard current === peripheral, foreground else { return }
        if characteristic.uuid == CBUUID(string: wire.responseUuid) {
            guard requestingToken, error == nil, let data = characteristic.value,
                let reply = wire.decode(hex: data.wireHex)
            else { return }
            if reply.kind == "token", let identity, let token = Data(wireHex: reply.dataHex) {
                do {
                    try credentials.save(
                        LanCredential(
                            station: identity.station, device: identity.device,
                            peripheral: peripheral.identifier.uuidString, token: token, port: Int(reply.port)))
                    finishTokenRequest("Wi-Fi access saved on this phone. Find this sensor on Wi-Fi to sync.")
                    onCredentialSaved?()
                } catch { finishTokenRequest("Wi-Fi access could not be saved securely. Try again.") }
            } else {
                finishTokenRequest("The sensor rejected Wi-Fi access. Check pairing and try again.")
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
                disconnect(message: "Unsupported or invalid sensor information.")
                return
            }
            identity = wire.identity(json: json)
            state.wifiAccessChanged(
                available: identity != nil && control != nil && response != nil, busy: false,
                explanation: "Save access once over Bluetooth before using Wi-Fi on this phone.")
        case CBUUID(string: state.liveUuid):
            state.receiveLive(json: json)
            freshnessTimer?.invalidate()
            freshnessTimer = Timer.scheduledTimer(withTimeInterval: 35, repeats: false) { [weak self] _ in
                Task { @MainActor in self?.state.readingExpired() }
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
