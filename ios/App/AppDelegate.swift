import OpenSensorShared
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    override init() {
        AppLanguage.align()
        super.init()
    }

    func application(
        _ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "OpenSensor", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    /// One shared engine, so LAN and Bluetooth save into the same archive and never sync a station twice at once.
    private let engine = IosArchiveKt.sharedSensorSync()
    private lazy var bluetooth = BluetoothCompanion(engine: engine)
    private lazy var lan = LanDiscovery(state: bluetooth.state, engine: engine)

    func scene(
        _ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let controller = MainViewControllerKt.mainViewController(
            state: bluetooth.state,
            scan: { [weak self] in self?.bluetooth.scan() },
            connect: { [weak self] id in self?.bluetooth.connect(id: id) },
            disconnect: { [weak self] in self?.bluetooth.disconnect() },
            scanLan: { [weak self] in self?.lan.scan() },
            saveWifiAccess: { [weak self] in self?.bluetooth.saveWifiAccess() },
            connectLan: { [weak self] name in self?.lan.connect(name: name) },
            syncLan: { [weak self] name in self?.lan.sync(name: name) },
            disconnectLan: { [weak self] name in self?.lan.disconnect(name: name) },
            syncBluetooth: { [weak self] in self?.syncConnectedSensor() }
        )
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        bluetooth.suspend()
        lan.enterBackground()
    }
    func sceneDidBecomeActive(_ scene: UIScene) {
        bluetooth.resume()
        lan.resume()
    }
    func sceneDidDisconnect(_ scene: UIScene) {
        bluetooth.suspend()
        lan.suspend()
    }

    /// Wi-Fi is faster, so an open, idle Wi-Fi session for the same sensor takes the sync.
    private func syncConnectedSensor() {
        if let device = bluetooth.deviceId, let name = lan.connectedSession(device: device) {
            lan.sync(name: name)
        } else {
            bluetooth.sync()
        }
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
