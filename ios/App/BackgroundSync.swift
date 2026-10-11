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

/// A slot filled after a closure that reads it is created, read on the main thread only.
private final class Ref<Value: AnyObject> {
    var value: Value?
}

/// Carries a value Apple hands over without a Sendable promise to the main actor. Used only to
/// move a background task or a Kotlin callback from a system queue back to the main thread.
private final class MainHop<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}

/// Ends one background task exactly once. The expiration handler and the run's own completion
/// can both reach it, and only the first ends the task. [handle] is the run this task started, so
/// an expiry cancels that run and never a later one.
@MainActor
private final class TaskFinish {
    private let task: BGTask
    private var finished = false
    var handle: BackgroundRunHandle?

    init(_ task: BGTask) { self.task = task }

    var isFinished: Bool { finished }

    func complete(success: Bool) {
        guard !finished else { return }
        finished = true
        task.setTaskCompleted(success: success)
    }
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
    /// A Collect all pass, from its first step until its last one ended.
    private var collectRunning = false
    /// Steps ended and steps planned in the pass going now.
    private var collectCounts = (done: 0, total: 0)
    /// The continued task iOS took for this pass and its progress, once it launched.
    private var collectTask: BGTask?
    private var collectProgress: Progress?
    /// True once iOS took a continued task for the pass going now, so it may run off screen.
    private(set) var collectAllContinues = false
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
        devices.collectAllChanged = { [weak self] done, total in
            self?.collectAllChanged(done: Int(truncating: done), total: Int(truncating: total))
        }
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
        let finish = TaskFinish(task)
        task.expirationHandler = {
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard !finish.isFinished else { return }
                    self.cancelRun(finish.handle)
                    self.schedule(after: AutoSyncSchedule.shared.periodSeconds)
                    finish.complete(success: false)
                }
            }
        }
        let handle = bridge.runDue(only: nil) { [weak self] summary in
            MainActor.assumeIsolated {
                guard let self else { return finish.complete(success: false) }
                guard !finish.isFinished else { return }
                self.clearRun(finish.handle)
                self.post(summary)
                if summary.retry, attempt < Int(AutoSyncSchedule.shared.maxRetries) {
                    self.retryAttempt = attempt + 1
                    self.schedule(after: AutoSyncSchedule.shared.retrySeconds(attempt: Int32(attempt)))
                } else {
                    self.retryAttempt = 0
                    self.schedule(after: AutoSyncSchedule.shared.periodSeconds)
                }
                self.armDue()
                finish.complete(success: true)
            }
        }
        finish.handle = handle
        run = handle
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

    /// Pending connects for the switched on sensors that are due and not woken within the period
    /// (`AutoSyncSettings.armable`), so a sensor in range does not wake the app again and again.
    /// [except] is the sensor whose wake is finishing.
    private func armDue(except: String? = nil) {
        bluetooth.armPendingConnects(ids: settings.armable(except: except))
    }

    /// A pending connect reached a sensor while the app was off screen. Its run gets a background
    /// time lease. A sensor that is not due, or a wake during another run, releases the link.
    private func wake(_ id: String) {
        guard run == nil, settings.shouldWake(peripheral: id) else {
            bluetooth.releaseWaiting(id: id)
            return
        }
        beginLease()
        let current = Ref<BackgroundRunHandle>()
        let handle = bridge.runWake(id: id) { [weak self] summary in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.clearRun(current.value)
                self.bluetooth.releaseWaiting(id: id)
                self.post(summary)
                self.armDue(except: id)
                self.endLease()
            }
        }
        current.value = handle
        run = handle
    }

    // MARK: Sync now in background

    /// One sensor, switch on or off. iOS 26 and later show it as a continued task with a Cancel
    /// button. Earlier releases, or a refused submit, run it under the background time lease the
    /// app gets on leaving. True only when iOS took the continued task, so the screen can say the
    /// user may leave. A run already going answers false and starts nothing.
    func syncInBackground(_ id: String) -> Bool {
        guard run == nil else { return false }
        if #available(iOS 26, *), submitContinued(id) { return true }
        beginLease()
        let current = Ref<BackgroundRunHandle>()
        let handle = bridge.runOne(id: id) { [weak self] summary in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.clearRun(current.value)
                self.post(summary)
                self.endLease()
            }
        }
        current.value = handle
        run = handle
        return false
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
        let finish = TaskFinish(task)
        let box = MainHop(task)
        // Cancel in the system UI, or expiry. The step ends and a link it opened closes.
        task.expirationHandler = {
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard !finish.isFinished else { return }
                    self.cancelRun(finish.handle)
                    finish.complete(success: false)
                }
            }
        }
        let handle = bridge.runOne(id: id) { [weak self] summary in
            MainActor.assumeIsolated {
                guard let self else { return finish.complete(success: false) }
                guard !finish.isFinished else { return }
                self.clearRun(finish.handle)
                box.value.progress.completedUnitCount = box.value.progress.totalUnitCount
                self.post(summary)
                finish.complete(success: summary.failures == 0)
            }
        }
        finish.handle = handle
        run = handle
    }

    private func progressed(done: Int, total: Int) {
        guard let progress else { return }
        progress.totalUnitCount = Int64(max(total, 1))
        progress.completedUnitCount = Int64(min(done, max(total, 1)))
    }

    // MARK: Collect all

    /// A Collect all pass started, moved on or ended. On iOS 26 the user's pass submits one continued
    /// task, so it goes on off screen with the system progress and Cancel. Earlier releases keep the
    /// foreground pass, which ends when the app leaves.
    private func collectAllChanged(done: Int, total: Int) {
        guard total > 0 else {
            collectRunning = false
            collectCounts = (0, 0)
            setCollectOffScreen(false)
            if let task = collectTask {
                collectTask = nil
                collectProgress = nil
                task.setTaskCompleted(success: true)
            }
            return
        }
        collectCounts = (done, total)
        if !collectRunning {
            collectRunning = true
            var continues = false
            if #available(iOS 26, *) { continues = submitCollect() }
            setCollectOffScreen(continues)
        }
        collectProgress?.totalUnitCount = Int64(total)
        collectProgress?.completedUnitCount = Int64(min(done, total))
    }

    private func setCollectOffScreen(_ on: Bool) {
        collectAllContinues = on
        bluetooth.collectOffScreen = on
        lan.collectOffScreen = on
    }

    /// Registers a fresh identifier for this pass and submits it, as for one sensor.
    @available(iOS 26, *)
    private func submitCollect() -> Bool {
        let identifier = "\(Self.continuedPrefix).\(UUID().uuidString)"
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) {
            [weak self] task in
            MainActor.assumeIsolated {
                guard let self, let continued = task as? BGContinuedProcessingTask else {
                    return task.setTaskCompleted(success: false)
                }
                self.runCollect(continued)
            }
        }
        guard registered else { return false }
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: progressTitle, subtitle: "")
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            return true
        } catch {
            logger.error("continued collect not started: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    @available(iOS 26, *)
    private func runCollect(_ task: BGContinuedProcessingTask) {
        // The pass may have ended before iOS launched its task.
        guard collectRunning, collectTask == nil else { return task.setTaskCompleted(success: !collectRunning) }
        collectTask = task
        collectProgress = task.progress
        task.progress.totalUnitCount = Int64(max(collectCounts.total, 1))
        task.progress.completedUnitCount = Int64(collectCounts.done)
        let id = ObjectIdentifier(task)
        // Cancel in the system UI, or expiry. The pass and the step in flight end at once.
        task.expirationHandler = {
            DispatchQueue.main.async {
                MainActor.assumeIsolated { [weak self] in self?.collectExpired(id) }
            }
        }
    }

    private func collectExpired(_ id: ObjectIdentifier) {
        guard let task = collectTask, ObjectIdentifier(task) == id else { return }
        collectTask = nil
        collectProgress = nil
        devices.cancelCollectAll()
        bluetooth.cancelCollect()
        lan.cancelCollect()
        task.setTaskCompleted(success: false)
    }

    // MARK: Shared pieces

    private func isOwned(_ id: String) -> Bool {
        if bluetooth.isBusy(id: id) { return true }
        guard let device = devices.deviceFor(peripheral: id) else { return false }
        return lan.holds(device: device)
    }

    /// Cancels the run going now. With [handle], only when that run is still the current one, so
    /// a late expiry never stops a run that started after it.
    private func cancelRun(_ handle: BackgroundRunHandle? = nil) {
        if let handle, run !== handle { return }
        run?.cancel()
        run = nil
        progress = nil
        bluetooth.cancelBackground()
        lan.cancelBackground()
        endLease()
    }

    /// A run reported. It stops being the current one only when it still is.
    private func clearRun(_ handle: BackgroundRunHandle?) {
        guard let handle, run === handle else { return }
        run = nil
        progress = nil
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
