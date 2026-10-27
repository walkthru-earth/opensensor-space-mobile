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
    static let processingId = "space.opensensor.app.autosync"
    /// The Collect all continued task on iOS 26 and later. Each pass adds a unique suffix to this
    /// prefix, which Info.plist permits as `space.opensensor.app.sync.*`.
    static let continuedPrefix = "space.opensensor.app.sync"
    /// The summary notification. A new one replaces the last, and a tap opens My devices.
    nonisolated static let notificationThread = "autosync"
    nonisolated static let pausedThread = "sync.paused"

    private let bluetooth: BluetoothCompanion
    private let lan: LanDiscovery
    private let devices: DevicesBridge
    /// The shared collections, set at launch. Auto-sync skips a sensor that a run holds.
    var runs: SensorCollections?
    private let logger = Logger(subsystem: "space.opensensor.app", category: "autosync")
    /// The raw UNAuthorizationStatus, refreshed when the app comes back and after the prompt.
    private var notificationStatus = UNAuthorizationStatus.notDetermined.rawValue
    private var progressTitle = "Syncing sensors"
    private var run: BackgroundRunHandle?
    private var lease: UIBackgroundTaskIdentifier = .invalid
    /// A Collect all pass, from its first step until its last one ended.
    private var collectRunning = false
    /// Steps ended and steps planned in the pass going now.
    private var collectCounts = (done: 0, total: 0)
    /// The continued task iOS took for this pass and its progress, once it launched.
    private var collectTask: BGTask?
    private var collectProgress: Progress?
    /// True once iOS launched the continued task for the pass going now, so it may run off screen.
    /// A submitted task that has not launched yet does not count.
    private(set) var collectAllContinues = false
    /// The pass submitted its continued task and iOS has not launched it yet.
    private var collectSubmitPending = false
    /// No run is going.
    private var idle: Bool { run == nil }
    /// The user's Collect all pass keeps going when the app leaves, under its continued task or
    /// one about to launch. Otherwise the pass ends with the screen.
    var collectAllHeld: Bool { collectAllContinues || collectSubmitPending }
    private var retryAttempt: Int {
        get { UserDefaults.standard.integer(forKey: "autosync.retry.v1") }
        set { UserDefaults.standard.set(newValue, forKey: "autosync.retry.v1") }
    }

    private(set) lazy var settings: AutoSyncSettings = AutoSyncIosKt.autoSyncSettings(
        notificationStatus: { [weak self] in KotlinLong(value: Int64(self?.notificationStatus ?? 0)) },
        requestNotifications: { [weak self] done in self?.askNotifications(done: done) },
        changed: { [weak self] in self?.settingsChanged() }
    )

    private lazy var bridge: BackgroundSyncBridge = AutoSyncIosKt.backgroundSyncBridge(
        devices: devices, settings: settings,
        browse: { [weak self] timeoutMs, done in
            guard let self else {
                _ = done([], [])
                return
            }
            self.lan.backgroundBrowse(timeout: Double(timeoutMs.int64Value) / 1000) { names, things in
                _ = done(names, things)
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

    init(bluetooth: BluetoothCompanion, lan: LanDiscovery, devices: DevicesBridge) {
        self.bluetooth = bluetooth
        self.lan = lan
        self.devices = devices
        bluetooth.onWake = { [weak self] id in self?.wake(id) }
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
        scheduleUnlessPending()
    }

    /// A launch keeps a request already waiting, so opening the app never pushes its start back.
    private func scheduleUnlessPending() {
        let id = Self.processingId
        BGTaskScheduler.shared.getPendingTaskRequests { [weak self] requests in
            let pending = requests.contains { $0.identifier == id }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, !pending || !self.hasSensors else { return }
                    self.schedule(after: AutoSyncSchedule.shared.periodSeconds)
                }
            }
        }
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
        guard idle else {
            // A wake or a user request is already running. Try again next period.
            schedule(after: AutoSyncSchedule.shared.periodSeconds)
            return task.setTaskCompleted(success: true)
        }
        let attempt = Int(retryAttempt)
        let finish = TaskFinish(task)
        task.expirationHandler = { @Sendable in
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
        guard idle, settings.shouldWake(peripheral: id) else {
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

    // MARK: Collect all

    /// A Collect all pass started, moved on or ended. On iOS 26 the user's pass submits one continued
    /// task, so it goes on off screen with the system progress and Cancel. Earlier releases keep the
    /// foreground pass, which ends when the app leaves.
    private func collectAllChanged(done: Int, total: Int) {
        guard total > 0 else {
            collectRunning = false
            collectSubmitPending = false
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
            // Off screen only once iOS launched the task. Leaving before that keeps the step going
            // under its own background time, as a run the user started.
            if #available(iOS 26, *) { collectSubmitPending = submitCollect() }
            setCollectOffScreen(false)
        }
        collectProgress?.totalUnitCount = Int64(total)
        collectProgress?.completedUnitCount = Int64(min(done, total))
    }

    private func setCollectOffScreen(_ on: Bool) {
        collectAllContinues = on
        bluetooth.collectOffScreen = on
        lan.collectOffScreen = on
    }

    /// Registers a fresh identifier for this pass and submits it. Continued task registrations are
    /// exempt from the launch time rule, and a unique suffix never registers the same id twice.
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
        collectSubmitPending = false
        guard collectRunning, collectTask == nil else { return task.setTaskCompleted(success: !collectRunning) }
        collectTask = task
        setCollectOffScreen(true)
        collectProgress = task.progress
        task.progress.totalUnitCount = Int64(max(collectCounts.total, 1))
        task.progress.completedUnitCount = Int64(collectCounts.done)
        let id = ObjectIdentifier(task)
        // Cancel in the system UI, or expiry. The pass and the step in flight end at once.
        task.expirationHandler = { @Sendable in
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

    /// True while a run, a session or the user's work holds this sensor, so Auto-sync skips it.
    private func isOwned(_ id: String) -> Bool {
        if bluetooth.isHeld(id: id) { return true }
        guard let device = devices.deviceFor(peripheral: id) else { return false }
        return runs?.isActive(device: ModelThingId.BleAddress(address: device)) == true || lan.hasSession(device: device)
    }

    /// Cancels the run going now. With [handle], only when that run is still the current one, so
    /// a late expiry never stops a run that started after it.
    private func cancelRun(_ handle: BackgroundRunHandle? = nil) {
        if let handle, run !== handle { return }
        run?.cancel()
        run = nil
        bluetooth.cancelBackground()
        lan.cancelBackground()
        endLease()
    }

    /// A run reported. It stops being the current one only when it still is.
    private func clearRun(_ handle: BackgroundRunHandle?) {
        guard let handle, run === handle else { return }
        run = nil
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

    /// Tells the user that iOS paused a sync off screen. Never asks for permission, and stays quiet
    /// unless notifications are already allowed. One notice replaces the last.
    func notifyPaused(title: String, body: String) {
        let allowed: [UNAuthorizationStatus] = [.authorized, .provisional, .ephemeral]
        guard allowed.contains(where: { $0.rawValue == notificationStatus }) else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.threadIdentifier = Self.pausedThread
        let request = UNNotificationRequest(identifier: "sync.paused", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    /// Back on screen, the paused notice has served its purpose.
    func clearPaused() {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["sync.paused"])
    }

    private func refreshNotificationStatus(then done: (@MainActor () -> Void)? = nil) {
        UNUserNotificationCenter.current().getNotificationSettings { current in
            let raw = current.authorizationStatus.rawValue
            DispatchQueue.main.async {
                self.notificationStatus = raw
                done?()
            }
        }
    }

    /// The system prompt, shown only from the tap that turns a switch on. [done] runs on the main thread.
    private func askNotifications(done: @escaping () -> KotlinUnit) {
        // Made on the main actor, so Kotlin's callback stays there.
        let callback: @MainActor () -> Void = { _ = done() }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in
            DispatchQueue.main.async {
                self.refreshNotificationStatus(then: callback)
            }
        }
    }
}
