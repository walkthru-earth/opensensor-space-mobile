import CoreBluetooth
import OpenSensorShared
import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate, @preconcurrency UNUserNotificationCenterDelegate {
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

    /// A summary posted while the app is on screen still shows as a banner.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list])
    }

    /// Tapping an Auto-sync summary opens My devices.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.notification.request.content.threadIdentifier == AutoSync.notificationThread {
            services.devices.openMyDevices()
        }
        completionHandler()
    }
}

/// The app's adapters, made once per process. Only ids, names and stations cross into Kotlin.
@MainActor
final class AppServices {
    /// One shared engine, so LAN and Bluetooth save into the same archive and never sync a station twice at once.
    let engine = IosArchiveKt.sharedSensorSync()
    private(set) lazy var bluetooth = BluetoothCompanion(engine: engine)
    private(set) lazy var lan = LanDiscovery(state: bluetooth.state, engine: engine)
    let credentials = LanCredentials()
    /// My devices over the adapters. Only ids, names and stations cross, never a token.
    private(set) lazy var devices: DevicesBridge = DevicesBridgeIosKt.devicesBridge(
        state: bluetooth.state, sync: engine,
        startScan: { [weak self] in self?.bluetooth.scan() },
        stopScan: { [weak self] in self?.bluetooth.stopScan() },
        forget: { [weak self] peripheral, device in self?.forget(peripheral: peripheral, device: device) },
        collect: { [weak self] id, wifiName in self?.collect(id: id, wifiName: wifiName) }
    )
    private(set) lazy var autoSync = AutoSync(bluetooth: bluetooth, lan: lan, devices: devices)

    /// Before launch ends. Registers the background task, and creates the central manager when iOS
    /// restores Bluetooth or a switch is on and access was already given, so no prompt shows here.
    func launch(restoringBluetooth: Bool) {
        bluetooth.onSensorOpened = { [weak self] peripheral, name, station, device in
            self?.devices.sensorOpened(peripheral: peripheral, name: name, station: station, device: device)
        }
        bluetooth.onCredentialSaved = { [weak self] in self?.credentialsChanged() }
        bluetooth.onCollectFinished = { [weak self] id, ok in self?.devices.collectFinished(id: id, ok: ok) }
        lan.onCollectFinished = { [weak self] id, ok in self?.devices.collectFinished(id: id, ok: ok) }
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

    /// The user confirmed Forget. The bridge already dropped its record. This closes any link to the
    /// sensor, turns its Auto-sync off and removes its Wi-Fi access. The system bond stays until
    /// removed in iOS Settings.
    private func forget(peripheral: String, device: String?) {
        bluetooth.close(id: peripheral)
        autoSync.forget(peripheral: peripheral)
        if let device {
            lan.disconnect(device: device)
            credentials.remove(device: device)
        }
        credentialsChanged()
    }

    /// One Collect all step. Wi-Fi when the plan found the sensor there with access, else Bluetooth.
    private func collect(id: String, wifiName: String?) {
        if let wifiName {
            lan.collect(name: wifiName, id: id)
        } else {
            bluetooth.collect(id: id)
        }
    }

    /// Wi-Fi is faster, so an open, idle Wi-Fi session for the same sensor takes the sync.
    func syncConnectedSensor() {
        if let device = bluetooth.deviceId, let name = lan.connectedSession(device: device) {
            lan.sync(name: name)
        } else {
            bluetooth.sync()
        }
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
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
            openSensor: { id in bluetooth.open(id: id) },
            disconnect: { bluetooth.disconnect() },
            scanLan: { lan.scan() },
            saveWifiAccess: { bluetooth.saveWifiAccess() },
            connectLan: { name in lan.connect(name: name) },
            syncLan: { name in lan.sync(name: name) },
            disconnectLan: { name in lan.disconnect(name: name) },
            syncBluetooth: { [weak services] in services?.syncConnectedSensor() },
            syncInBackground: { [weak services] id in KotlinBoolean(value: services?.autoSync.syncInBackground(id) ?? false) },
            closeIdle: { id in bluetooth.closeIdle(id: id) },
            openDebugLink: { device in bluetooth.openDebugLink(device: device) }
        )
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        guard let services else { return }
        // Collect all is a foreground pass. The step in flight ends with the link below.
        services.devices.cancelCollectAll()
        services.bluetooth.suspend()
        services.lan.enterBackground()
        services.autoSync.enteredBackground()
    }
    func sceneDidBecomeActive(_ scene: UIScene) {
        guard let services else { return }
        services.bluetooth.resume()
        services.lan.resume()
        services.autoSync.becameActive()
    }
    func sceneDidDisconnect(_ scene: UIScene) {
        guard let services else { return }
        services.devices.cancelCollectAll()
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
