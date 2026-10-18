import CoreBluetooth
import Foundation
import OpenSensorShared

/// Why the physical link ended a connection by itself. The main actor maps it to the shared notice.
enum BleLoss: Sendable {
    case unknownSensor, connectFailed, disconnected, serviceMissing, characteristicsMissing, notifyFailed, readFailed,
        tokenFailed
    /// The sensor refused this phone's pairing keys. Pairing again from iOS Settings fixes it, so no retry.
    case pairingRemoved
}

/// What the Bluetooth queue tells the main actor, in arrival order. Connection events carry the
/// connection number they belong to, so a late callback from an ended connection is ignored.
enum BleEvent: Sendable {
    case power(CBManagerState)
    case found(id: String, name: String, rssi: Int32)
    /// An armed pending connect completed. [restored] when it completed before this launch.
    case wake(id: String, restored: Bool)
    case connected
    /// INFO was read. [canSync] when the control and response characteristics exist.
    case info(json: String, name: String?, mtu: Int32, canSync: Bool)
    /// The discovery reads finished.
    case ready
    case live(String)
    case status(String)
    /// The GATT queue became idle or busy.
    case gatt(idle: Bool)
    /// The answer to the native token request. The token never leaves this process.
    case token(kind: String, payload: Data, port: Int)
    /// The connection already ended on the Bluetooth queue.
    case lost(BleLoss)
}

/// The Core Bluetooth side of the one Bluetooth connection (#80). The central manager, its
/// peripherals, the GATT queue, write completions, notifications and state restoration run on one
/// serial queue, which is this actor's executor. Callbacks enter with `assumeIsolated`, which traps
/// on the wrong queue. RESPONSE frames reach the shared [IosLink] here, without a main-thread hop.
/// `BluetoothCompanion` keeps UI and coordination on the main actor and hears [BleEvent]s.
actor BlePhysicalLink {
    private let queue: DispatchSerialQueue
    nonisolated var unownedExecutor: UnownedSerialExecutor { queue.asUnownedSerialExecutor() }

    private let deliver: @Sendable (UInt64, BleEvent) -> Void
    private let uuids: Uuids
    private let wire = SyncWire()
    private var callbacks: Callbacks?
    private var central: CBCentralManager?
    private var discovered: [String: CBPeripheral] = [:]
    private var advertisedNames: [String: String] = [:]
    /// Pending connects armed off screen, or restored by iOS. They complete when the sensor is in range.
    private var waiting: [String: CBPeripheral] = [:]
    /// What [arm] asked for, kept across a radio off and on.
    private var armed = ArmedConnects()
    private var current: CBPeripheral?
    /// Peripherals whose cancel was issued and whose disconnect callback has not arrived yet. That late
    /// callback belongs to the ended connection, so it never ends a newer one to the same sensor.
    private var cancelling: Set<UUID> = []
    /// The main actor's number for the connection in [current]. Zero when none.
    private var connection: UInt64 = 0
    private var control: CBCharacteristic?
    private var response: CBCharacteristic?
    /// The snapshot characteristics, kept for paced reads (#159).
    private var live: CBCharacteristic?
    private var status: CBCharacteristic?
    private var responseSubscribed = false
    /// True until the discovery reads finished.
    private var discovering = false
    private var gatt = BleGattQueue<CBCharacteristic>()
    private var reportedIdle = true
    /// Set after INFO. Every RESPONSE frame but the token reply goes to it.
    private var sink: IosLink?
    /// True while the native token request waits for its reply.
    private var nativeReply = false
    /// Peripherals that answered an encrypted read before. Only for these can a refused encryption
    /// mean a broken bond. Ids only, no secrets.
    private var bonded: Set<String>
    private static let bondedKey = "ble.bonded.v1"

    struct Uuids: Sendable {
        let service: String
        let info: String
        let live: String
        let status: String
        let control: String
        let response: String
    }

    init(uuids: Uuids, deliver: @escaping @Sendable (UInt64, BleEvent) -> Void) {
        queue = DispatchSerialQueue(label: "earth.walkthru.opensensor.ble")
        self.uuids = uuids
        self.deliver = deliver
        bonded = Set(UserDefaults.standard.stringArray(forKey: Self.bondedKey) ?? [])
    }

    // MARK: Main actor entry points. Each hops onto the queue, so calls keep their order.

    /// Creates the central manager. The first time, iOS asks for Bluetooth access.
    nonisolated func start(restoreId: String) {
        queue.async { [self] in
            assumeIsolated {
                guard $0.central == nil else { return }
                let callbacks = Callbacks(link: $0)
                $0.callbacks = callbacks
                $0.central = CBCentralManager(
                    delegate: callbacks, queue: $0.queue,
                    options: [
                        CBCentralManagerOptionShowPowerAlertKey: false,
                        CBCentralManagerOptionRestoreIdentifierKey: restoreId,
                    ])
            }
        }
    }

    nonisolated func scan() {
        queue.async { [self] in
            assumeIsolated {
                guard let central = $0.central, central.state == .poweredOn else { return }
                $0.discovered.removeAll()
                central.scanForPeripherals(withServices: [CBUUID(string: $0.uuids.service)])
            }
        }
    }

    nonisolated func stopScan() {
        queue.async { [self] in
            assumeIsolated {
                guard let central = $0.central, central.state == .poweredOn else { return }
                central.stopScan()
            }
        }
    }

    /// Opens this sensor as [number]. A pending connect that already reached it is adopted. Any
    /// other connection ends first, because this app holds one Bluetooth link.
    nonisolated func connect(id: String, as number: UInt64) {
        queue.async { [self] in assumeIsolated { $0.open(id: id, number: number) } }
    }

    /// Ends connection [number], if it is still the current one. Queued writes fail.
    nonisolated func disconnect(_ number: UInt64) {
        queue.async { [self] in
            assumeIsolated {
                guard $0.connection == number else { return }
                $0.end()
            }
        }
    }

    /// Hands connection [number] its shared link, and subscribes to RESPONSE before its first write.
    /// Called before Kotlin can see the link, so no write from [sender] arrives earlier.
    nonisolated func attach(_ link: IosLink, sender: UInt64, to number: UInt64) {
        queue.async { [self] in
            assumeIsolated {
                guard $0.connection == number, $0.current != nil, $0.sink == nil, let response = $0.response else {
                    return
                }
                $0.sink = link
                $0.gatt.attach(sender: sender)
                if !$0.responseSubscribed { $0.gatt.append(.subscribe(response)) }
                $0.performNext()
            }
        }
    }

    /// One Kotlin request body from port [sender]. [done] hears once whether the sensor took it.
    nonisolated func send(_ body: Data, from sender: UInt64, done: @escaping @Sendable (Bool) -> Void) {
        queue.async { [self] in
            assumeIsolated {
                $0.gatt.write(body, to: $0.current == nil ? nil : $0.control, from: sender, done: done)
                $0.performNext()
            }
        }
    }

    /// Writes `GET_TOKEN` for connection [number]. Its reply stays native.
    nonisolated func requestToken(_ number: UInt64) {
        queue.async { [self] in
            assumeIsolated {
                guard $0.connection == number, $0.current != nil, let control = $0.control else {
                    $0.deliver(number, .lost(.tokenFailed))
                    return
                }
                $0.nativeReply = true
                $0.gatt.append(.write(control, $0.wire.tokenRequest(), done: nil))
                $0.performNext()
            }
        }
    }

    /// The token request ended, so every later RESPONSE frame goes to the shared link again.
    nonisolated func endTokenRequest(_ number: UInt64) {
        queue.async { [self] in
            assumeIsolated {
                guard $0.connection == number else { return }
                $0.nativeReply = false
            }
        }
    }

    /// Reads `live` then `status` once for connection [number], only while discovery is over and no
    /// other GATT operation is queued or in flight. Their values arrive as `.live` and `.status`.
    nonisolated func readSnapshots(_ number: UInt64) {
        queue.async { [self] in
            assumeIsolated {
                guard $0.connection == number, $0.current != nil, !$0.discovering, let live = $0.live,
                    let status = $0.status, $0.gatt.readSnapshots(live, status)
                else { return }
                $0.performNext()
            }
        }
    }

    /// Arms pending connects to these sensors. They never time out.
    nonisolated func arm(ids: [String]) {
        queue.async { [self] in
            assumeIsolated {
                $0.armed.arm(ids)
                $0.connectWaiting(ids)
            }
        }
    }

    /// Connects these sensors as pending connects while the radio is on. Off, they wait in [armed].
    private func connectWaiting(_ ids: [String]) {
        guard let central, central.state == .poweredOn else { return }
        let uuids = ids.compactMap { UUID(uuidString: $0) }
        for peripheral in central.retrievePeripherals(withIdentifiers: uuids) {
            let id = peripheral.identifier.uuidString
            guard peripheral !== current, waiting[id] == nil else { continue }
            waiting[id] = peripheral
            central.connect(peripheral)
        }
    }

    /// Ends one pending connect, or every one when [id] is nil, except the current connection.
    nonisolated func releaseWaiting(id: String?) {
        queue.async { [self] in
            assumeIsolated { link in
                link.armed.release(id)
                let ids = id.map { [$0] } ?? Array(link.waiting.keys)
                for id in ids {
                    guard let peripheral = link.waiting[id], peripheral !== link.current else { continue }
                    link.waiting[id] = nil
                    link.cancel(peripheral)
                }
            }
        }
    }

    // MARK: Connection state (always on `queue`)

    private func open(id: String, number: UInt64) {
        if current != nil { end() }
        guard let central, central.state == .poweredOn else {
            deliver(number, .lost(.connectFailed))
            return
        }
        armed.adopted(id)
        let peripheral =
            waiting.removeValue(forKey: id) ?? discovered[id]
            ?? UUID(uuidString: id).flatMap { central.retrievePeripherals(withIdentifiers: [$0]).first }
        guard let peripheral else {
            deliver(number, .lost(.unknownSensor))
            return
        }
        connection = number
        current = peripheral
        peripheral.delegate = callbacks
        if cancelling.contains(peripheral.identifier) {
            // An earlier cancel is still in flight. Its disconnect callback connects this one. If iOS
            // never answers a cancelled pending connect, connect anyway after a short wait.
            let id = peripheral.identifier
            queue.asyncAfter(deadline: .now() + Self.cancelAnswerWait) { [self] in
                assumeIsolated { $0.connectAfterCancel(id, number: number) }
            }
            return
        }
        if peripheral.state == .connected {
            // A pending connect already reached the sensor. Discovery starts as after a fresh connect.
            deliver(number, .connected)
            peripheral.discoverServices([CBUUID(string: uuids.service)])
        } else {
            central.connect(peripheral)
        }
    }

    /// Cancels a connection or pending connect. When Core Bluetooth will answer with a disconnect
    /// callback, the peripheral is marked so that callback cannot end a newer connection.
    private func cancel(_ peripheral: CBPeripheral) {
        guard let central, central.state == .poweredOn else { return }
        if peripheral.state == .connected || peripheral.state == .connecting {
            cancelling.insert(peripheral.identifier)
        }
        central.cancelPeripheralConnection(peripheral)
    }

    private func connectAfterCancel(_ id: UUID, number: UInt64) {
        guard connection == number, let peripheral = current, peripheral.identifier == id,
            cancelling.remove(id) != nil, peripheral.state != .connected
        else { return }
        central?.connect(peripheral)
    }

    private static let cancelAnswerWait: DispatchTimeInterval = .seconds(2)

    /// The negotiated ATT MTU. Core Bluetooth reports it as the write without response limit plus the
    /// 3-byte header. The with response value is 512 on iOS (long writes), never the MTU. The shared
    /// contract caps `payload_max = min(MTU - 3, 512)`, so the reported MTU never exceeds 515.
    private static func attMtu(_ peripheral: CBPeripheral) -> Int {
        min(peripheral.maximumWriteValueLength(for: .withoutResponse), 512) + 3
    }

    /// True when this callback answers an earlier cancel. A connection opened since then connects now.
    private func answersCancel(_ peripheral: CBPeripheral) -> Bool {
        guard cancelling.remove(peripheral.identifier) != nil else { return false }
        if current === peripheral, peripheral.state != .connected { central?.connect(peripheral) }
        return true
    }

    /// True when Core Bluetooth's bond signal means this sensor refused keys that once worked.
    private func broken(_ signal: BleBondFailure.Signal, _ peripheral: CBPeripheral) -> Bool {
        signal.isBroken(previouslyBonded: bonded.contains(peripheral.identifier.uuidString))
    }

    /// Ends the current connection on this queue. Queued writes fail and no port may write again.
    private func end() {
        gatt.reset()
        reportedIdle = true
        sink = nil
        nativeReply = false
        responseSubscribed = false
        discovering = false
        control = nil
        response = nil
        live = nil
        status = nil
        if let peripheral = current {
            current = nil
            peripheral.delegate = nil
            cancel(peripheral)
        }
        connection = 0
    }

    /// The bond is broken. The shared link ends with the typed auth cause before any write
    /// completion can report it as a local fault, so no run retries it.
    private func loseBond() {
        sink?.linkLost(reason: IosLink.companion.REASON_BOND_MISSING)
        lose(.pairingRemoved)
    }

    /// The connection failed here. It ends at once, then the main actor hears why.
    private func lose(_ loss: BleLoss) {
        let number = connection
        end()
        deliver(number, .lost(loss))
    }

    private func performNext() {
        if let peripheral = current, let operation = gatt.next() {
            switch operation {
            case .read(let characteristic): peripheral.readValue(for: characteristic)
            case .subscribe(let characteristic): peripheral.setNotifyValue(true, for: characteristic)
            case .write(let characteristic, let data, _):
                peripheral.writeValue(data, for: characteristic, type: .withResponse)
            }
        }
        let idle = gatt.isIdle
        guard idle != reportedIdle, connection != 0 else { return }
        reportedIdle = idle
        deliver(connection, .gatt(idle: idle))
    }

    // MARK: Core Bluetooth callbacks (always on `queue`)

    fileprivate func stateChanged(_ central: CBCentralManager) {
        let state = central.state
        switch state {
        case .poweredOn, .unknown, .resetting: break
        default:
            waiting.removeAll()
            discovered.removeAll()
        }
        // No disconnect callback follows a cancel once the radio is gone.
        if state != .poweredOn { cancelling.removeAll() }
        if state != .poweredOn, current != nil {
            // Replies already offered still reach their callers, then the run sees the radio go.
            sink?.linkLost(reason: IosLink.companion.REASON_RADIO_BLUETOOTH)
            end()
        }
        deliver(0, .power(state))
        if state == .poweredOn {
            // A restored pending connect that completed while the app was not running.
            for (id, peripheral) in waiting where peripheral.state == .connected && peripheral !== current {
                deliver(0, .wake(id: id, restored: true))
            }
            // Pending connects the radio dropped when it turned off wait again.
            connectWaiting(armed.rearm(waiting: Set(waiting.keys), current: current?.identifier.uuidString))
        }
    }

    fileprivate func restore(_ peripherals: [CBPeripheral]) {
        // iOS relaunched the app for a pending connect. Keep the peripherals so the run can use them.
        for peripheral in peripherals { waiting[peripheral.identifier.uuidString] = peripheral }
        armed.arm(peripherals.map { $0.identifier.uuidString })
    }

    fileprivate func found(_ peripheral: CBPeripheral, name: String?, rssi: Int32) {
        let id = peripheral.identifier.uuidString
        discovered[id] = peripheral
        let name = name ?? peripheral.name ?? "AQ sensor"
        advertisedNames[id] = name
        deliver(0, .found(id: id, name: name, rssi: rssi))
    }

    fileprivate func connected(_ peripheral: CBPeripheral) {
        let id = peripheral.identifier.uuidString
        if current !== peripheral, waiting[id] != nil {
            // The main actor decides whether the run takes it or it ends.
            deliver(0, .wake(id: id, restored: false))
            return
        }
        guard current === peripheral else {
            cancel(peripheral)
            return
        }
        deliver(connection, .connected)
        peripheral.discoverServices([CBUUID(string: uuids.service)])
    }

    fileprivate func failedToConnect(_ peripheral: CBPeripheral, bond: BleBondFailure.Signal) {
        if answersCancel(peripheral) { return }
        guard current === peripheral else {
            waiting[peripheral.identifier.uuidString] = nil
            return
        }
        if broken(bond, peripheral) { return loseBond() }
        lose(.connectFailed)
    }

    fileprivate func disconnected(_ peripheral: CBPeripheral, bond: BleBondFailure.Signal) {
        if answersCancel(peripheral) { return }
        guard current === peripheral else {
            waiting[peripheral.identifier.uuidString] = nil
            return
        }
        if broken(bond, peripheral) { return loseBond() }
        // Replies already offered still reach their callers, then the run sees the peer go.
        sink?.linkLost(reason: IosLink.companion.REASON_PEER_CLOSED)
        lose(.disconnected)
    }

    fileprivate func discoveredServices(_ peripheral: CBPeripheral, failed: Bool, bond: BleBondFailure.Signal) {
        guard current === peripheral else { return }
        if broken(bond, peripheral) { return loseBond() }
        guard !failed, let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: uuids.service) })
        else { return lose(.serviceMissing) }
        peripheral.discoverCharacteristics(
            [
                CBUUID(string: uuids.info), CBUUID(string: uuids.live), CBUUID(string: uuids.status),
                CBUUID(string: uuids.control), CBUUID(string: uuids.response),
            ], for: service)
    }

    fileprivate func discoveredCharacteristics(_ peripheral: CBPeripheral, service: CBService, failed: Bool, bond: BleBondFailure.Signal) {
        guard current === peripheral else { return }
        if broken(bond, peripheral) { return loseBond() }
        let characteristics = service.characteristics ?? []
        guard !failed,
            let info = characteristics.first(where: { $0.uuid == CBUUID(string: uuids.info) }),
            let live = characteristics.first(where: { $0.uuid == CBUUID(string: uuids.live) }),
            let status = characteristics.first(where: { $0.uuid == CBUUID(string: uuids.status) }),
            info.properties.contains(.read), live.properties.contains([.read, .notify]),
            status.properties.contains([.read, .notify])
        else { return lose(.characteristicsMissing) }
        control = characteristics.first(where: {
            $0.uuid == CBUUID(string: uuids.control) && $0.properties.contains(.write)
        })
        response = characteristics.first(where: {
            $0.uuid == CBUUID(string: uuids.response) && $0.properties.contains(.notify)
        })
        self.live = live
        self.status = status
        // Serialize discovery follow-up, encrypted subscription and reads through their delegate callbacks.
        discovering = true
        gatt.start([.read(info), .subscribe(live), .read(live), .subscribe(status), .read(status)])
        performNext()
    }

    fileprivate func wrote(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, failed: Bool, bond: BleBondFailure.Signal) {
        guard current === peripheral else { return }
        if broken(bond, peripheral) { return loseBond() }
        switch gatt.finish(write: characteristic, ok: !failed) {
        case .stale: return
        // The request state on the sensor is unknown, so the shared link is poisoned. The run ends
        // as a lost link, and the main actor closes the connection with it.
        case .linkFailed: performNext()
        case .nativeFailed: lose(.tokenFailed)
        case .written: performNext()
        }
    }

    fileprivate func notifying(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, failed: Bool, bond: BleBondFailure.Signal) {
        guard current === peripheral else { return }
        if broken(bond, peripheral) { return loseBond() }
        guard !failed, characteristic.isNotifying else { return lose(.notifyFailed) }
        if characteristic.uuid == CBUUID(string: uuids.response) { responseSubscribed = true }
        if gatt.finish(subscribe: characteristic) { performNext() }
    }

    fileprivate func updated(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, failed: Bool, bond: BleBondFailure.Signal) {
        guard current === peripheral else { return }
        if broken(bond, peripheral) { return loseBond() }
        if characteristic.uuid == CBUUID(string: uuids.response) {
            guard !failed, let data = characteristic.value else { return }
            // Only the token reply stays native. Every other frame goes to the shared link, which
            // drains replies a cancelled run left behind. Bluetooth cannot pause notifications, and
            // the read window bounds them, so a full inbox is left to poison the link.
            guard nativeReply, let reply = wire.decode(data: data), reply.answersToken else {
                _ = sink?.offer(frame: data)
                return
            }
            nativeReply = false
            deliver(connection, .token(kind: reply.kind, payload: reply.payload, port: Int(reply.port)))
            return
        }
        guard !failed, let data = characteristic.value else { return lose(.readFailed) }
        guard let json = String(data: data, encoding: .utf8) else {
            // INFO identifies the sensor, so an unreadable one ends the link. One bad LIVE or STATUS
            // value is dropped, and a discovery read of it still completes so the queue moves on.
            if characteristic.uuid == CBUUID(string: uuids.info) { return lose(.readFailed) }
            return readDone(characteristic)
        }
        switch characteristic.uuid {
        case CBUUID(string: uuids.info):
            let id = peripheral.identifier.uuidString
            // Every characteristic needs an encrypted link, so a read INFO proves a working bond.
            if bonded.insert(id).inserted {
                UserDefaults.standard.set(Array(bonded).sorted(), forKey: Self.bondedKey)
            }
            deliver(
                connection,
                .info(
                    json: json, name: advertisedNames[id] ?? peripheral.name,
                    mtu: Int32(Self.attMtu(peripheral)),
                    canSync: control != nil && response != nil))
        case CBUUID(string: uuids.live):
            deliver(connection, .live(json))
        case CBUUID(string: uuids.status):
            sink?.offerStatus(json: json)
            deliver(connection, .status(json))
        default: return
        }
        readDone(characteristic)
    }

    /// Ends a read in the GATT queue. A notification answers no read, so it changes nothing.
    private func readDone(_ characteristic: CBCharacteristic) {
        guard gatt.finish(read: characteristic) else { return }
        if discovering, characteristic.uuid == CBUUID(string: uuids.status) {
            // STATUS is the last discovery read. A RESPONSE subscription may still follow it.
            discovering = false
            deliver(connection, .ready)
        }
        performNext()
    }
}

/// Invariant. Core Bluetooth hands these objects over on the actor's own queue, and `assumeIsolated`
/// checks that queue before they are used, so they never leave it.
private struct Delivered<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}

/// Core Bluetooth's delegate for [BlePhysicalLink]. An actor cannot be an `NSObject`, so this
/// forwards each callback into the actor. The central runs on the actor's queue, so `assumeIsolated`
/// holds, and traps if a callback ever arrives elsewhere.
private final class Callbacks: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private weak let link: BlePhysicalLink?

    init(link: BlePhysicalLink) {
        self.link = link
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let central = Delivered(central)
        link?.assumeIsolated { $0.stateChanged(central.value) }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let peripherals = Delivered(dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [])
        link?.assumeIsolated { $0.restore(peripherals.value) }
    }

    func centralManager(
        _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any], rssi RSSI: NSNumber
    ) {
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let rssi = RSSI.int32Value
        let peripheral = Delivered(peripheral)
        link?.assumeIsolated { $0.found(peripheral.value, name: name, rssi: rssi) }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let peripheral = Delivered(peripheral)
        link?.assumeIsolated { $0.connected(peripheral.value) }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let bond = BleBondFailure.signal(error)
        let peripheral = Delivered(peripheral)
        link?.assumeIsolated { $0.failedToConnect(peripheral.value, bond: bond) }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let bond = BleBondFailure.signal(error)
        let peripheral = Delivered(peripheral)
        link?.assumeIsolated { $0.disconnected(peripheral.value, bond: bond) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let failed = error != nil, bond = BleBondFailure.signal(error)
        let peripheral = Delivered(peripheral)
        link?.assumeIsolated { $0.discoveredServices(peripheral.value, failed: failed, bond: bond) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let failed = error != nil, bond = BleBondFailure.signal(error)
        let objects = Delivered((peripheral, service))
        link?.assumeIsolated {
            $0.discoveredCharacteristics(objects.value.0, service: objects.value.1, failed: failed, bond: bond)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        let failed = error != nil, bond = BleBondFailure.signal(error)
        let objects = Delivered((peripheral, characteristic))
        link?.assumeIsolated {
            $0.wrote(objects.value.0, characteristic: objects.value.1, failed: failed, bond: bond)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?
    ) {
        let failed = error != nil, bond = BleBondFailure.signal(error)
        let objects = Delivered((peripheral, characteristic))
        link?.assumeIsolated {
            $0.notifying(objects.value.0, characteristic: objects.value.1, failed: failed, bond: bond)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let failed = error != nil, bond = BleBondFailure.signal(error)
        let objects = Delivered((peripheral, characteristic))
        link?.assumeIsolated {
            $0.updated(objects.value.0, characteristic: objects.value.1, failed: failed, bond: bond)
        }
    }
}

/// The Bluetooth side of one [IosLink]. Kotlin calls it from any thread. Each write hops onto the
/// Bluetooth queue and joins the GATT queue in call order. [sender] names this port, so a write
/// from an earlier link or connection fails instead of reaching the next one.
final class BlePort: NSObject, IosLinkPort, Sendable {
    private weak let physical: BlePhysicalLink?
    let sender: UInt64
    let mtu: Int32

    init(physical: BlePhysicalLink, sender: UInt64, mtu: Int32) {
        self.physical = physical
        self.sender = sender
        self.mtu = mtu
    }

    func send(frame: Data, done: @escaping (KotlinBoolean) -> Void) {
        let reply = WriteDone(done)
        guard let physical else {
            reply.call(false)
            return
        }
        physical.send(frame, from: sender) { reply.call($0) }
    }

    /// Notifications cannot be paused. The read window the engine asks for bounds them.
    func setReadPaused(paused: Bool) {}
}
