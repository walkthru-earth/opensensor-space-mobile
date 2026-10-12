import CoreBluetooth
import OpenSensorShared
import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// The adapters live with the app, not the scene, because iOS can launch the app for a
    /// background task or a Bluetooth restoration without showing a screen.
    let services = AppServices()

    override init() {
        AppLanguage.align()
        super.init()
    }

    func application(
        _ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        services.launch(restoringBluetooth: launchOptions?[.bluetoothCentrals] != nil)
        return true
    }

    func application(
        _ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "OpenSensor", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }

    /// A summary posted while the app is on screen still shows as a banner. The notification center
    /// does not promise the main thread, so both callbacks are nonisolated.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list])
    }

    /// Tapping an Auto-sync summary opens My devices.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.notification.request.content.threadIdentifier == AutoSync.notificationThread {
            Task { @MainActor in self.services.devices.openMyDevices() }
        }
        completionHandler()
    }
}

/// The app's adapters, made once per process. Only ids, names and stations cross into Kotlin.
@MainActor
final class AppServices {
    private var openAttempt = UUID()
    private var selectedOpenId: String?
    /// One shared engine, so LAN and Bluetooth save into the same archive and never sync a station twice at once.
    let engine = IosArchiveKt.sharedSensorSync()
    private(set) lazy var bluetooth = BluetoothCompanion(engine: engine)
    private(set) lazy var lan = LanDiscovery(state: bluetooth.state, engine: engine)
    let credentials = LanCredentials()
    /// My devices over the adapters. Only ids, names and stations cross, never a token.
    private(set) lazy var devices: DevicesBridge = DevicesBridgeIosKt.devicesBridge(
        state: bluetooth.state, sync: engine,
        startScan: { [weak self] in self?.scanDevices() },
        stopScan: { [weak self] in self?.stopDeviceScan() },
        forget: { [weak self] peripheral, device in self?.forget(peripheral: peripheral, device: device) },
        collect: { [weak self] id, wifiName in self?.collect(id: id, wifiName: wifiName) }
    )
    private(set) lazy var autoSync = AutoSync(bluetooth: bluetooth, lan: lan, devices: devices)
    /// One shared collection controller per device (#81), for every sync this app starts. Swift
    /// lends its open links, dials a new one on a retry and gives each run its background time.
    private(set) lazy var runs: SensorCollections = engine.collections(
        pick: { [weak self] device, prefer in
            let transport = RunTransport(prefer)
            return MainActor.assumeIsolated { self?.syncLink(device: device, transport: transport) }
        },
        dial: { [weak self] device, prefer, done in
            let transport = RunTransport(prefer)
            let answer = DialAnswer { opened in _ = done(KotlinBoolean(value: opened)) }
            MainActor.assumeIsolated {
                guard let self else { return answer.call(false) }
                self.dial(device: device, transport: transport, done: answer.call)
            }
        },
        protect: { [weak self] device, id, unattended in
            MainActor.assumeIsolated {
                KotlinBoolean(value: self?.protect(device: device, id: id.int64Value, unattended: unattended.boolValue) ?? false)
            }
        },
        unprotect: { [weak self] id in MainActor.assumeIsolated { self?.unprotect(id.int64Value) } }
    )
    /// UIKit background time by run, for runs the user started.
    private var protections: [Int64: UIBackgroundTaskIdentifier] = [:]
    /// The paused notice in the app's language, read once at launch so the expiry handler never waits.
    private var pausedWords: (title: String, body: String)?

    /// Before launch ends. Registers the background task, and creates the central manager when iOS
    /// restores Bluetooth or a switch is on and access was already given, so no prompt shows here.
    func launch(restoringBluetooth: Bool) {
        bluetooth.runs = runs
        lan.runs = runs
        autoSync.runs = runs
        runs.pausedNotice { [weak self] title, body in
            MainActor.assumeIsolated { self?.pausedWords = (title, body) }
        }
        bluetooth.peripheralFor = { [weak self] device in self?.devices.peripheralFor(device: device) }
        bluetooth.onSensorOpened = { [weak self] peripheral, name, station, device in
            guard let self else { return SensorOpenResult(accepted: false, notice: .infoInvalid, credentialToRemove: nil) }
            let result = self.devices.sensorOpened(peripheral: peripheral, name: name, station: station, device: device)
            if let staleDevice = result.credentialToRemove {
                self.lan.disconnect(device: staleDevice)
                self.credentials.remove(device: staleDevice)
                self.credentialsChanged()
            }
            return result
        }
        bluetooth.onCredentialSaved = { [weak self] in self?.credentialsChanged() }
        bluetooth.onCollectFinished = { [weak self] id, ok in self?.devices.collectFinished(id: id, ok: ok) }
        lan.onCollectFinished = { [weak self] id, ok in
            guard let self else { return }
            if ok || !self.devices.collectRetries(id: id) {
                self.devices.collectFinished(id: id, ok: ok)
            } else {
                // A partial LAN attempt can continue over the bonded BLE link, unless the pass was
                // cancelled. The shared collector skips verified files and resumes retained bytes.
                self.bluetooth.releaseIdleForCollect(next: id)
                self.bluetooth.collect(id: id)
            }
        }
        lan.onCredentialRemoved = { [weak self] in self?.credentialsChanged() }
        credentialsChanged()
        bluetooth.launchedOffScreen()
        autoSync.register()
        if restoringBluetooth || (autoSync.hasSensors && CBManager.authorization == .allowedAlways) {
            bluetooth.requestAccess()
        }
    }

    /// Tells My devices which sensors hold Wi-Fi access. Ids only, the token stays in the Keychain.
    func credentialsChanged() {
        let entries = credentials.list()
        devices.credentialsChanged(
            peripherals: entries.map(\.peripheral), stations: entries.map(\.station), devices: entries.map(\.device))
    }

    /// My devices uses one scan action for both nearby Bluetooth and local Bonjour sensors.
    /// The browse is the user-facing action that lets iOS request Local Network access.
    private func scanDevices() {
        bluetooth.scan()
        lan.scan()
    }

    private func stopDeviceScan() {
        bluetooth.stopScan()
        lan.stop()
    }

    /// The user confirmed Forget. The bridge already dropped its record. This closes any link to the
    /// sensor, turns its Auto-sync off and removes its Wi-Fi access. The system bond stays until
    /// removed in iOS Settings.
    private func forget(peripheral: String, device: String?) {
        cancelPendingOpen(id: peripheral)
        bluetooth.close(id: peripheral)
        autoSync.forget(peripheral: peripheral)
        if let device {
            runs.dropInterrupted(device: device)
            lan.disconnect(device: device)
            credentials.remove(device: device)
        }
        credentialsChanged()
    }

    /// The user rotated the sensor's token in Device settings. The stored one is dead, so its Wi-Fi
    /// session closes and the Keychain entry goes before the new token is read over Bluetooth.
    func forgetWifiAccess(device: String) {
        lan.disconnect(device: device)
        credentials.remove(device: device)
        credentialsChanged()
    }

    /// One Collect all step. Wi-Fi when the plan found the sensor there with access, else Bluetooth.
    private func collect(id: String, wifiName: String?) {
        if let wifiName {
            lan.collect(name: wifiName, id: id)
        } else {
            bluetooth.releaseIdleForCollect(next: id)
            bluetooth.collect(id: id)
        }
    }

    /// Prefer a discovered, enrolled LAN endpoint. Start Bluetooth only when there is no usable
    /// local target or this open's authenticated handshake fails. A newer open invalidates the
    /// earlier attempt, so an old failure cannot take over a different sensor's Bluetooth link.
    func openSensor(id: String, allowBluetooth: Bool = true) {
        openAttempt = UUID()
        selectedOpenId = id
        let attempt = openAttempt
        if let station = devices.stationFor(peripheral: id), let device = devices.deviceFor(peripheral: id) {
            let started = lan.connectKnown(station: station, device: device, onFailure: allowBluetooth ? { [weak self] in
                guard let self, self.openAttempt == attempt,
                    self.selectedOpenId?.caseInsensitiveCompare(id) == .orderedSame else { return }
                self.bluetooth.open(id: id)
            } : nil)
            if started {
                bluetooth.closeIdle(id: id)
                return
            }
        }
        if allowBluetooth { bluetooth.open(id: id) }
    }

    /// Device settings explicitly needs bonded Bluetooth for GET_TOKEN and token rotation.
    func openBluetoothSensor(id: String) {
        cancelPendingOpen()
        selectedOpenId = id
        bluetooth.requestAccess()
        bluetooth.open(id: id)
    }

    func disconnectSensor(id: String) {
        cancelPendingOpen(id: id)
        if let device = devices.deviceFor(peripheral: id) { lan.disconnect(device: device) }
        bluetooth.close(id: id)
    }

    /// The last page of this sensor is gone. Its Bluetooth link and Wi-Fi session close now, or
    /// once the sync, command or lease on them ends.
    func closeIdle(id: String) {
        cancelPendingOpen(id: id)
        bluetooth.closeIdle(id: id)
        if let device = devices.deviceFor(peripheral: id) { lan.closeIdle(device: device) }
    }

    func cancelPendingOpen(id: String? = nil) {
        if let id, selectedOpenId?.caseInsensitiveCompare(id) != .orderedSame { return }
        openAttempt = UUID()
        selectedOpenId = nil
    }

    func commandSensor(id: String, command: SensorCommand) {
        guard let device = devices.deviceFor(peripheral: id) else {
            bluetooth.state.today.commandRefused()
            return
        }
        if lan.command(device: device, command: command) { return }
        if lan.hasSession(device: device) || runs.isActive(device: device) {
            bluetooth.state.today.commandRefused()
            return
        }
        if bluetooth.deviceId == device {
            bluetooth.command(command)
        } else {
            bluetooth.state.today.commandRefused()
        }
    }

    /// Wi-Fi is faster, so an open, idle Wi-Fi session for the same sensor takes the sync.
    func syncSensor(id: String) {
        guard let device = devices.deviceFor(peripheral: id) else {
            bluetooth.state.today.commandRefused()
            return
        }
        if runs.isActive(device: device) {
            bluetooth.state.today.commandRefused()
        } else if let name = lan.connectedSession(device: device) {
            lan.sync(name: name)
        } else if lan.hasSession(device: device) {
            bluetooth.state.today.commandRefused()
        } else if bluetooth.deviceId == device {
            bluetooth.sync()
        } else {
            bluetooth.state.today.commandRefused()
        }
    }

    func syncOneFile(id: String, name: String) {
        guard let device = devices.deviceFor(peripheral: id), !runs.isActive(device: device) else { return }
        if let session = lan.connectedSession(device: device) {
            lan.sync(name: session, onlyName: name)
        } else if !lan.hasSession(device: device), bluetooth.deviceId == device {
            bluetooth.sync(fileName: name)
        }
    }

    func cancelSync(id: String) {
        guard let device = devices.deviceFor(peripheral: id) else { return }
        lan.cancelInteractiveSync(device: device)
        if bluetooth.deviceId == device { bluetooth.cancelInteractiveSync() }
    }

    /// The open connection's shared link to this sensor, Wi-Fi first. Debug, Device settings and
    /// Files take leases of it.
    func linkFor(device: String) -> IosLink? {
        lan.link(device: device) ?? bluetooth.link(device: device)
    }

    /// The open link of the kind a run allows, Wi-Fi first when either will do.
    private func syncLink(device: String, transport: RunTransport) -> IosLink? {
        switch transport {
        case .lan: lan.syncLink(device: device)
        case .ble: bluetooth.syncLink(device: device)
        case .any: lan.syncLink(device: device) ?? bluetooth.syncLink(device: device)
        }
    }

    /// A run's retry found no open link. Wi-Fi when the run allows it and the sensor is on this network.
    private func dial(device: String, transport: RunTransport, done: @escaping (Bool) -> Void) {
        if transport == .lan || (transport == .any && lan.canDial(device: device)) {
            lan.dial(device: device, done: done)
        } else {
            bluetooth.dial(device: device, done: done)
        }
    }

    /// Background time for a run. Unattended runs and a continued Collect all already hold their
    /// BGTask. A run the user started takes a UIKit background task, and iOS refusing one while
    /// the app is off screen refuses the run.
    private func protect(device: String, id: Int64, unattended: Bool) -> Bool {
        if unattended || autoSync.collectAllContinues { return true }
        let task = UIApplication.shared.beginBackgroundTask(withName: "Sensor sync") { [weak self] in
            // UIKit invokes the expiration handler on the main thread.
            MainActor.assumeIsolated { self?.protectionExpired(device: device, id: id) }
        }
        if task == .invalid { return UIApplication.shared.applicationState != .background }
        protections[id] = task
        return true
    }

    /// iOS ended the time off screen. The run ends as interrupted, keeps what it saved and starts
    /// again when the scene is active. The notice shows only when notifications are already allowed.
    private func protectionExpired(device: String, id: Int64) {
        // A continued Collect all task launched since, and it keeps the app running.
        if autoSync.collectAllContinues { return unprotect(id) }
        if runs.interrupt(device: device), let words = pausedWords {
            autoSync.notifyPaused(title: words.title, body: words.body)
        }
        unprotect(id)
    }

    /// Back on screen. Each run iOS interrupted starts again over its own transport, through the
    /// entry point the screens follow. The shared controller skips the files already saved.
    func resumeInterrupted() {
        autoSync.clearPaused()
        for interrupted in runs.takeInterrupted() {
            let device = interrupted.device
            let onlyName = interrupted.onlyName
            switch RunTransport(interrupted.prefer) {
            case .lan:
                _ = lan.resumeSync(device: device, onlyName: onlyName)
            case .ble:
                _ = bluetooth.resumeSync(device: device, onlyName: onlyName)
            case .any:
                if !lan.resumeSync(device: device, onlyName: onlyName) {
                    _ = bluetooth.resumeSync(device: device, onlyName: onlyName)
                }
            }
        }
    }

    private func unprotect(_ id: Int64) {
        guard let task = protections.removeValue(forKey: id) else { return }
        UIApplication.shared.endBackgroundTask(task)
    }
}

/// The transports a run allows, read from Kotlin before the hop to the main actor.
private enum RunTransport: Sendable {
    case lan, ble, any

    init(_ prefer: SyncLinkPreference) {
        self = prefer == .lan ? .lan : prefer == .ble ? .ble : .any
    }
}

/// Invariant. Kotlin's dial answer is safe from any thread, and its provider ignores a second call.
private final class DialAnswer: @unchecked Sendable {
    let call: (Bool) -> Void
    init(_ call: @escaping (Bool) -> Void) { self.call = call }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let stickerScanner = StickerScanner()
    private var services: AppServices? { (UIApplication.shared.delegate as? AppDelegate)?.services }

    func scene(
        _ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene, let services else { return }
        // Opened from a tapped summary while the app was not running.
        if connectionOptions.notificationResponse?.notification.request.content.threadIdentifier
            == AutoSync.notificationThread
        {
            services.devices.openMyDevices()
        }
        let bluetooth = services.bluetooth
        let lan = services.lan
        let controller = MainViewControllerKt.mainViewController(
            state: bluetooth.state,
            devices: services.devices,
            autoSync: services.autoSync.settings,
            requestBluetooth: { bluetooth.requestAccess() },
            openSensor: { [weak services] id in services?.openSensor(id: id) },
            openBluetoothSensor: { [weak services] id in services?.openBluetoothSensor(id: id) },
            openLanOnly: { [weak services] id in services?.openSensor(id: id, allowBluetooth: false) },
            disconnectSensor: { [weak services] id in services?.disconnectSensor(id: id) },
            disconnect: { bluetooth.disconnect() },
            scanLan: { lan.scan() },
            saveWifiAccess: { bluetooth.saveWifiAccess() },
            connectLan: { name in lan.connect(name: name) },
            syncLan: { name in lan.sync(name: name) },
            disconnectLan: { name in lan.disconnect(name: name) },
            syncBluetooth: { [weak services] id in services?.syncSensor(id: id) },
            syncInBackground: { [weak services] id in KotlinBoolean(value: services?.autoSync.syncInBackground(id) ?? false) },
            closeIdle: { [weak services] id in services?.closeIdle(id: id) },
            linkFor: { [weak services] device in services?.linkFor(device: device) },
            command: { [weak services] id, command in services?.commandSensor(id: id, command: command) },
            syncOneFile: { [weak services] id, name in services?.syncOneFile(id: id, name: name) },
            cancelSync: { [weak services] id in services?.cancelSync(id: id) },
            forgetWifiAccess: { [weak services] device in services?.forgetWifiAccess(device: device) },
            // Nil on hardware without VisionKit data scanning, which hides Scan on the sticker card.
            scanSticker: StickerScanner.isSupported
                ? { [weak self] onResult in
                    // Kotlin exports the nested callback as returning KotlinUnit.
                    KotlinBoolean(value: self?.scanSticker { value in _ = onResult(value) } ?? false)
                }
                : nil
        )
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
    }

    /// Presents the sticker scanner over this scene's screen. Only the decoded string reaches Kotlin.
    private func scanSticker(onResult: @escaping (String?) -> Void) -> Bool {
        stickerScanner.scan(from: window?.rootViewController, onResult: onResult)
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        guard let services else { return }
        services.cancelPendingOpen()
        // Collect all is a foreground pass, unless iOS 26 took it as a continued task or is about to
        // launch one. Otherwise the step in flight ends with the link below.
        if !services.autoSync.collectAllHeld { services.devices.cancelCollectAll() }
        services.bluetooth.suspend()
        services.lan.enterBackground()
        services.autoSync.enteredBackground()
    }
    func sceneDidBecomeActive(_ scene: UIScene) {
        guard let services else { return }
        services.bluetooth.resume()
        services.lan.resume()
        services.autoSync.becameActive()
        services.resumeInterrupted()
    }
    func sceneDidDisconnect(_ scene: UIScene) {
        guard let services else { return }
        services.cancelPendingOpen()
        if !services.autoSync.collectAllContinues { services.devices.cancelCollectAll() }
        services.bluetooth.suspend()
        services.lan.suspend()
    }
}

/// One language for the whole app (docs/cross-platform-guide.md, Languages and right to left).
///
/// UIKit takes the first of the phone's languages that the app ships (`CFBundleLocalizations`) for
/// the layout direction and the purpose strings. Compose resources read only the first preferred
/// language. With French then Arabic that would mix English shared screens with a right-to-left
/// layout and Arabic prompts. `align` puts the language the bundle resolved first, through the
/// argument domain, which lasts for this launch and is never saved, so the user's own per-app
/// language choice stays theirs. That choice already leads the list, so `align` leaves it alone.
enum AppLanguage {
    static func align() {
        guard let resolved = Bundle.main.preferredLocalizations.first else { return }
        let preferred = Locale.preferredLanguages
        guard let ordered = reorder(preferred, resolved) else { return }
        var arguments = UserDefaults.standard.volatileDomain(forName: UserDefaults.argumentDomain)
        arguments["AppleLanguages"] = ordered
        UserDefaults.standard.setVolatileDomain(arguments, forName: UserDefaults.argumentDomain)
    }

    /// `languages` with the first tag in `language` moved to the front, or nil when it already leads
    /// or is absent (then English applies everywhere and nothing mixes).
    static func reorder(_ languages: [String], _ language: String) -> [String]? {
        func code(_ tag: String) -> String? { Locale(identifier: tag).language.languageCode?.identifier }
        guard let index = languages.firstIndex(where: { code($0) == code(language) }), index > 0 else { return nil }
        var ordered = languages
        ordered.insert(ordered.remove(at: index), at: 0)
        return ordered
    }
}
