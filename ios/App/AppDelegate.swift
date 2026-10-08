import UIKit
import OpenSensorShared

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "OpenSensor", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let bluetooth = BluetoothCompanion()
    private lazy var lan = LanDiscovery(state: bluetooth.state)

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let controller = MainViewControllerKt.mainViewController(
            state: bluetooth.state,
            scan: { [weak self] in self?.bluetooth.scan() },
            connect: { [weak self] id in self?.bluetooth.connect(id: id) },
            disconnect: { [weak self] in self?.bluetooth.disconnect() },
            scanLan: { [weak self] in self?.lan.scan() }
        )
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
    }

    func sceneDidEnterBackground(_ scene: UIScene) { bluetooth.suspend(); lan.stop() }
    func sceneDidBecomeActive(_ scene: UIScene) { bluetooth.resume() }
    func sceneDidDisconnect(_ scene: UIScene) { bluetooth.suspend(); lan.stop() }
}
