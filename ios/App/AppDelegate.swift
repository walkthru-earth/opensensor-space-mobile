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

final class SceneDelegate: UIResponder, UIWindowSceneDelegate, UIDocumentPickerDelegate {
    var window: UIWindow?
    /// One shared engine, so LAN and Bluetooth save into the same archive and never sync a station twice at once.
    private let engine = SensorSync()
    private lazy var bluetooth = BluetoothCompanion(engine: engine)
    private lazy var lan = LanDiscovery(state: bluetooth.state, engine: engine)
    private var exportFolder: URL?
    private var exportTask: Task<Void, Never>?
    private var preparedExport: (url: URL, count: Int)?

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
            exportArchive: { [weak self] in self?.exportArchive() },
            syncBluetooth: { [weak self] in self?.syncConnectedSensor() },
            openDebugLink: { [weak self] device in self?.bluetooth.openDebugLink(device: device) }
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
        presentExportIfReady()
    }
    func sceneDidDisconnect(_ scene: UIScene) {
        bluetooth.suspend()
        lan.suspend()
        exportTask?.cancel()
        if exportTask == nil { finishExport("Export cancelled. Collected files remain saved on this phone.") }
    }

    /// Wi-Fi is faster, so an open, idle Wi-Fi session for the same sensor takes the sync.
    private func syncConnectedSensor() {
        if let device = bluetooth.deviceId, let name = lan.connectedSession(device: device) {
            lan.sync(name: name)
        } else {
            bluetooth.sync()
        }
    }

    private func exportArchive() {
        guard exportTask == nil, exportFolder == nil else { return }
        bluetooth.state.exportChanged(busy: true, explanation: "Preparing your collected Parquet files…")
        exportTask = Task { [weak self] in
            guard let self else { return }
            defer { self.exportTask = nil }
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("opensensor-export-\(UUID())")
            self.exportFolder = folder
            do {
                let archive = try ArchiveIO(root: SensorArchive.defaultRoot())
                let snapshot = folder.appendingPathComponent("SensorArchive", isDirectory: true)
                let count = try await archive.export(to: snapshot)
                try Task.checkCancellation()
                guard count > 0 else {
                    self.finishExport("No completed Parquet files yet. Sync a sensor first.")
                    return
                }
                self.preparedExport = (snapshot, count)
                self.presentExportIfReady()
            } catch {
                self.finishExport("Could not prepare the export. Collected files remain saved. Try again.")
            }
        }
    }

    private func presentExportIfReady() {
        guard let snapshot = preparedExport else { return }
        guard window?.windowScene?.activationState == .foregroundActive else { return }
        guard let controller = window?.rootViewController, controller.presentedViewController == nil else {
            finishExport("Try exporting again after the current screen closes.")
            return
        }
        preparedExport = nil
        let picker = UIDocumentPickerViewController(forExporting: [snapshot.url], asCopy: true)
        picker.delegate = self
        bluetooth.state.exportChanged(busy: true, explanation: "Choose where to save \(snapshot.count) Parquet files.")
        controller.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        finishExport("Parquet files exported to your selected folder.")
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finishExport("Export cancelled. Collected files remain saved on this phone.")
    }

    private func finishExport(_ message: String) {
        if let folder = exportFolder { try? FileManager.default.removeItem(at: folder) }
        exportFolder = nil
        preparedExport = nil
        bluetooth.state.exportChanged(busy: false, explanation: message)
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
