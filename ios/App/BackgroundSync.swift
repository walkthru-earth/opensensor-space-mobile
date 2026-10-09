import BackgroundTasks
import Foundation
import OSLog
import OpenSensorShared
import UIKit
import UserNotifications

/// Step results built by the shared mapping (`BackgroundSteps`), so Swift never decides what a sync end means.
extension BackgroundStepResult {
    static func skipped(_ reason: String) -> BackgroundStepResult { BackgroundSteps.shared.skipped(reason: reason) }
    static func lost() -> BackgroundStepResult { BackgroundSteps.shared.lost() }
    static func ended(_ result: SyncResult, bytes: Int64) -> BackgroundStepResult {
        BackgroundSteps.shared.ended(result: result, bytes: bytes)
    }
}

/// Carries a value Apple hands over without a Sendable promise to the main actor. Used only to
/// move a background task or a Kotlin callback from a system queue back to the main thread.
private final class MainHop<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}

/// iOS Auto-sync (#23). Swift owns the OS services: BGTaskScheduler, the background time lease,
/// Bluetooth wakes and the summary notification. The shared `BackgroundSyncBridge` decides which
/// sensors are due, which link to try and what the summary says. Ids, names and counts cross,
/// never a PIN or token, and the log keeps names private. One instance lives for the whole process
/// (`AppServices`), so system callbacks hold it strongly.
@MainActor
final class AutoSync {
    /// The scheduled run, a `BGProcessingTask` (Info.plist `BGTaskSchedulerPermittedIdentifiers`).
    static let processingId = "earth.walkthru.opensensor.autosync"
    /// "Sync now in background" on iOS 26 and later. Each request adds a unique suffix to this prefix,
    /// which Info.plist permits as `earth.walkthru.opensensor.sync.*`.
    static let continuedPrefix = "earth.walkthru.opensensor.sync"
    /// The summary notification. A new one replaces the last, and a tap opens My devices.
    static let notificationThread = "autosync"

    private let bluetooth: BluetoothCompanion
    private let lan: LanDiscovery
    private let devices: DevicesBridge
    private let logger = Logger(subsystem: "earth.walkthru.opensensor", category: "autosync")
    /// The raw UNAuthorizationStatus, refreshed when the app comes back and after the prompt.
    private var notificationStatus = UNAuthorizationStatus.notDetermined.rawValue
    private var progressTitle = "Syncing sensors"
    private var run: BackgroundRunHandle?
    private var lease: UIBackgroundTaskIdentifier = .invalid
    /// The progress of a continued task, which iOS shows with a Cancel button.
    private var progress: Progress?
    private var retryAttempt: Int {
        get { UserDefaults.standard.integer(forKey: "autosync.retry.v1") }
        set { UserDefaults.standard.set(newValue, forKey: "autosync.retry.v1") }
    }

    private(set) lazy var settings: AutoSyncSettings = AutoSyncIosKt.autoSyncSettings(
        notificationStatus: { [weak self] in KotlinLong(value: Int64(self?.notificationStatus ?? 0)) },
        requestNotifications: { [weak self] done in self?.askNotifications(done: done) },
        changed: { [weak self] in self?.settingsChanged() },
        continuedTask: AutoSync.continuedAvailable
    )

    private lazy var bridge: BackgroundSyncBridge = AutoSyncIosKt.backgroundSyncBridge(
        devices: devices, settings: settings,
        browse: { [weak self] timeoutMs, done in
            guard let self else {
                _ = done([], [])
                return
            }
            self.lan.backgroundBrowse(timeout: Double(timeoutMs.int64Value) / 1000) { names, stations in
                _ = done(names, stations)
            }
        },
        sync: { [weak self] id, wifiName, done in
            guard let self else {
                _ = done(.skipped("ended"))
                return
            }
            if let wifiName {
                self.lan.backgroundSync(name: wifiName) { _ = done($0) }
            } else {
                self.bluetooth.backgroundSync(id: id) { _ = done($0) }
            }
        },
        isOwned: { [weak self] id in KotlinBoolean(value: self?.isOwned(id) ?? true) },
        log: { [weak self] line in self?.logger.info("\(line, privacy: .private)") }
    )

    static var continuedAvailable: Bool {
        if #available(iOS 26, *) { return true }
        return false
    }

    init(bluetooth: BluetoothCompanion, lan: LanDiscovery, devices: DevicesBridge) {
        self.bluetooth = bluetooth
        self.lan = lan
        self.devices = devices
        bluetooth.onWake = { [weak self] id in self?.wake(id) }
        bluetooth.onBackgroundProgress = { [weak self] done, total in self?.progressed(done: done, total: total) }
        lan.onBackgroundProgress = { [weak self] done, total in self?.progressed(done: done, total: total) }
    }

    /// Called from `application(_:didFinishLaunchingWithOptions:)`, before launch ends, as iOS requires.
    func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.processingId, using: .main) { [weak self] task in
            MainActor.assumeIsolated {
                guard let self else { return task.setTaskCompleted(success: false) }
                self.runScheduled(task)
            }
        }
        refreshNotificationStatus()
        bridge.progressTitle { [weak self] title in
            MainActor.assumeIsolated { self?.progressTitle = title }
        }
        schedule(after: AutoSyncSchedule.shared.periodSeconds)
    }

    /// True when any switch is on, so launch may create the central manager for state restoration.
    var hasSensors: Bool { !settings.enabledPeripherals().isEmpty }

    /// The app left the screen. Due Auto-sync sensors get a pending connect, so iOS wakes the app near one.
    func enteredBackground() { armDue() }

    /// Back on screen. The notification answer may have changed in Settings.
    func becameActive() { refreshNotificationStatus() }

    /// A sensor the user forgot loses its switch, and its pending connect ends.
    func forget(peripheral: String) {
        settings.forget(peripheral: peripheral)
        bluetooth.releaseWaiting(id: peripheral)
    }

    // MARK: Scheduled run

    private func runScheduled(_ task: BGTask) {
        guard run == nil else {
            // A wake or a user request is already running. Try again next period.
            schedule(after: AutoSyncSchedule.shared.periodSeconds)
            return task.setTaskCompleted(success: true)
        }
        let attempt = Int(retryAttempt)
        let box = MainHop(task)
        task.expirationHandler = {
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self.cancelRun()
                    self.schedule(after: AutoSyncSchedule.shared.periodSeconds)
                    box.value.setTaskCompleted(success: false)
                }
            }
        }
        run = bridge.runDue(only: nil) { [weak self] summary in
            MainActor.assumeIsolated {
                guard let self else { return box.value.setTaskCompleted(success: false) }
                self.run = nil
                self.post(summary)
                if summary.retry, attempt < Int(AutoSyncSchedule.shared.maxRetries) {
                    self.retryAttempt = attempt + 1
                    self.schedule(after: AutoSyncSchedule.shared.retrySeconds(attempt: Int32(attempt)))
                } else {
                    self.retryAttempt = 0
                    self.schedule(after: AutoSyncSchedule.shared.periodSeconds)
                }
                self.armDue()
                box.value.setTaskCompleted(success: true)
            }
        }
    }

    /// Asks iOS for the next run. Nothing is scheduled while every switch is off.
    private func schedule(after seconds: Double) {
        guard hasSensors else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.processingId)
            return
        }
        let request = BGProcessingTaskRequest(identifier: Self.processingId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: seconds)
        // Bluetooth needs neither. Wi-Fi sensors are tried when the phone is on that network.
        request.requiresNetworkConnectivity = false
        request.requiresExternalPower = false
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            logger.error("auto-sync not scheduled: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func settingsChanged() {
        retryAttempt = 0
        schedule(after: AutoSyncSchedule.shared.periodSeconds)
    }

    // MARK: Bluetooth wake

    /// Pending connects for the switched on sensors that are due. One that is not due waits for the
    /// scheduled run, so a sensor in range does not wake the app again and again.
    private func armDue() {
        let due = settings.enabledPeripherals().filter { settings.isDue(peripheral: $0) }
        bluetooth.armPendingConnects(ids: due)
    }

    /// A pending connect reached a sensor while the app was off screen. Its run gets a background
    /// time lease. A sensor that is not due, or a wake during another run, releases the link.
    private func wake(_ id: String) {
        guard run == nil, settings.isDue(peripheral: id) else {
            bluetooth.releaseWaiting(id: id)
            return
        }
        beginLease()
        run = bridge.runDue(only: [id]) { [weak self] summary in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.run = nil
                self.bluetooth.releaseWaiting(id: id)
                self.post(summary)
                self.armDue()
                self.endLease()
            }
        }
    }

    // MARK: Sync now in background

    /// One sensor, switch on or off. iOS 26 and later show it as a continued task with a Cancel
    /// button. Earlier releases run it under the background time lease the app gets on leaving.
    func syncInBackground(_ id: String) {
        guard run == nil else { return }
        if #available(iOS 26, *), submitContinued(id) { return }
        beginLease()
        run = bridge.runOne(id: id) { [weak self] summary in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.run = nil
                self.post(summary)
                self.endLease()
            }
        }
    }

    /// Registers a handler for a fresh identifier, then submits it. Continued task registrations are
    /// exempt from the launch time rule, and a unique suffix never registers the same id twice.
    @available(iOS 26, *)
    private func submitContinued(_ id: String) -> Bool {
        let identifier = "\(Self.continuedPrefix).\(UUID().uuidString)"
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) {
            [weak self] task in
            MainActor.assumeIsolated {
                guard let self, let continued = task as? BGContinuedProcessingTask else {
                    return task.setTaskCompleted(success: false)
                }
                self.runContinued(continued, id: id)
            }
        }
        guard registered else { return false }
        let request = BGContinuedProcessingTaskRequest(
            identifier: identifier, title: progressTitle, subtitle: bridge.sensorName(id: id))
        // Start now or not at all. When iOS cannot start it, the lease takes over.
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            return true
        } catch {
            logger.error("continued sync not started: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    @available(iOS 26, *)
    private func runContinued(_ task: BGContinuedProcessingTask, id: String) {
        guard run == nil else { return task.setTaskCompleted(success: false) }
        task.progress.totalUnitCount = 1
        task.progress.completedUnitCount = 0
        progress = task.progress
        let box = MainHop(task)
        // Cancel in the system UI, or expiry. The step ends and a link it opened closes.
        task.expirationHandler = {
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self.cancelRun()
                    box.value.setTaskCompleted(success: false)
                }
            }
        }
        run = bridge.runOne(id: id) { [weak self] summary in
            MainActor.assumeIsolated {
                guard let self else { return box.value.setTaskCompleted(success: false) }
                self.run = nil
                self.progress = nil
                box.value.progress.completedUnitCount = box.value.progress.totalUnitCount
                self.post(summary)
                box.value.setTaskCompleted(success: summary.failures == 0)
            }
        }
    }

    private func progressed(done: Int, total: Int) {
        guard let progress else { return }
        progress.totalUnitCount = Int64(max(total, 1))
        progress.completedUnitCount = Int64(min(done, max(total, 1)))
    }

    // MARK: Shared pieces

    private func isOwned(_ id: String) -> Bool {
        if bluetooth.isBusy(id: id) { return true }
        guard let device = devices.deviceFor(peripheral: id) else { return false }
        return lan.holds(device: device)
    }

    private func cancelRun() {
        run?.cancel()
        run = nil
        progress = nil
        bluetooth.cancelBackground()
        lan.cancelBackground()
        endLease()
    }

    private func beginLease() {
        guard lease == .invalid else { return }
        lease = UIApplication.shared.beginBackgroundTask(withName: "Auto-sync") { [weak self] in
            // UIKit invokes the expiration handler on the main thread.
            MainActor.assumeIsolated { self?.cancelRun() }
        }
    }

    private func endLease() {
        guard lease != .invalid else { return }
        let task = lease
        lease = .invalid
        UIApplication.shared.endBackgroundTask(task)
    }

    /// Shows the summary only for new files or failures. Words come from the shared catalogue.
    private func post(_ summary: BackgroundRunSummary) {
        guard summary.notify else { return }
        let content = UNMutableNotificationContent()
        content.title = summary.title
        content.body = summary.body
        content.threadIdentifier = Self.notificationThread
        let request = UNNotificationRequest(identifier: "autosync.summary", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    private func refreshNotificationStatus(then done: (() -> Void)? = nil) {
        let callback = MainHop(done)
        UNUserNotificationCenter.current().getNotificationSettings { current in
            let raw = current.authorizationStatus.rawValue
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self.notificationStatus = raw
                    callback.value?()
                }
            }
        }
    }

    /// The system prompt, shown only from the tap that turns a switch on. [done] runs on the main thread.
    private func askNotifications(done: @escaping () -> KotlinUnit) {
        let callback = MainHop(done)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self.refreshNotificationStatus { _ = callback.value() }
                }
            }
        }
    }
}
