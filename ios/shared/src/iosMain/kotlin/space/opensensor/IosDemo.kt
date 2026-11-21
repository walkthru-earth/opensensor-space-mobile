@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package space.opensensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import space.opensensor.collection.LinkPreference
import space.opensensor.demo.DemoSensor
import space.opensensor.model.ThingId
import kotlin.coroutines.CoroutineContext
import kotlin.native.Platform

/**
 * The shared demo sensor of `:core:demo` on iOS (#310), in a debug binary only ([enabled]).
 *
 * It stands in for the Swift Bluetooth adapter for one fixed peripheral id, [PERIPHERAL]. It reads
 * INFO, STATUS and LIVE into [CompanionState] as Swift does for a real sensor, records the sensor
 * through [opened], runs the shared upkeep once and serves an [IosLink] over the in-process board. So Today, Files, Device
 * settings, Debug and sync run the same shared code as for a real sensor, and Swift never sees it.
 * Like the Swift adapter it holds one connection, and opening it closes the real Bluetooth link.
 *
 * Call it on the main thread.
 */
internal class IosDemo(
    private val state: CompanionState,
    private val opened: (peripheral: String, name: String?, thing: String, device: String) -> SensorOpenResult,
    private val main: CoroutineContext = Dispatchers.Main,
    private val work: CoroutineContext = Dispatchers.Default,
    /** One board for the process, so its config, files and token persist across connections like NVS. */
    private val sensor: DemoSensor = DemoSensor(),
) {
    private val wire = SyncWire()
    private var scope: CoroutineScope? = null
    private var link: IosLink? = null
    private var run: SyncHandle? = null
    private var commandRun: SyncHandle? = null
    private var upkeepRun: SyncHandle? = null

    /** The board's hardware id, lowercase. */
    val device: String get() = sensor.deviceId

    fun owns(peripheral: String): Boolean = peripheral.equals(PERIPHERAL, ignoreCase = true)

    fun ownsDevice(device: String): Boolean = device.equals(sensor.deviceId, ignoreCase = true)

    /** The open, identified link to the demo, or null. */
    fun linkFor(device: String): IosLink? = link?.takeIf { ownsDevice(device) && it.isOpen }

    /** Connects to the board unless a connection is open or opening. */
    fun open() {
        if (scope != null) return
        state.connectionStarted(PERIPHERAL)
        val scope = CoroutineScope(main + SupervisorJob())
        this.scope = scope
        scope.launch {
            val info = try {
                withContext(work) {
                    sensor.connect()
                    sensor.readInfo().decodeToString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                close(CompanionNotice.CONNECT_FAILED)
                return@launch
            }
            val identity = wire.identity(info)
            if (identity == null || !opened(PERIPHERAL, sensor.displayName, identity.thing, identity.device).accepted || !state.receiveInfo(info)) {
                close(CompanionNotice.INFO_INVALID)
                return@launch
            }
            val port = DemoPort(sensor, scope)
            val link = IosLink(port, overWifi = false, address = identity.device, callbacks = main, context = work)
            port.attach(link)
            link.identify(identity.thing, identity.boot, identity.maxRead)
            this@IosDemo.link = link
            link.whenClosed { scope.launch { if (this@IosDemo.link === link) close(CompanionNotice.SENSOR_DISCONNECTED) } }
            state.receiveStatus(sensor.readStatus().decodeToString())
            state.receiveLive(sensor.readLive().decodeToString())
            state.bluetoothSyncAvailable(true)
            // The same quiet once-per-connection upkeep Swift starts for a Bluetooth link.
            var upkeep: SyncHandle? = null
            upkeep = engine?.upkeep(link) { if (upkeepRun === upkeep) upkeepRun = null }
            upkeepRun = upkeep
            launch { sensor.liveNotifications.collect { state.receiveLive(it.decodeToString()) } }
            launch {
                sensor.statusNotifications.collect {
                    val json = it.decodeToString()
                    state.receiveStatus(json)
                    link.offerStatus(json)
                }
            }
        }
    }

    /** Ends the connection and any sync on it. Today shows [notice] when the demo is the open sensor. */
    fun close(notice: CompanionNotice = CompanionNotice.DISCONNECTED) {
        val wasOpen = scope != null
        run?.cancel()
        run = null
        commandRun?.cancel()
        commandRun = null
        upkeepRun?.cancel()
        upkeepRun = null
        scope?.cancel()
        scope = null
        link?.close()
        link = null
        sensor.close()
        if (wasOpen && state.selectedId == PERIPHERAL) state.connectionClosedNoticed(notice)
    }

    /** The last page of the demo left. The connection stays while a sync or command holds it. */
    fun closeIdle() {
        if (run == null && commandRun == null) close()
    }

    /** Syncs the demo through the app's shared collections, as Swift does for a Bluetooth sensor. */
    fun sync(onlyName: String?) {
        val runs = runs
        if (runs == null || link?.isOpen != true || run != null || commandRun != null) {
            state.today.commandRefused()
            return
        }
        state.bluetoothSyncStarted()
        var ended = false
        val handle = runs.collect(
            ThingId.BleAddress(device),
            onlyName,
            LinkPreference.BLE,
            RunOrigin.USER,
            progress = state::bluetoothSyncProgressed,
            finished = { result ->
                ended = true
                run = null
                state.bluetoothSyncFinished(result)
            },
        )
        if (!ended) run = handle
    }

    /** A confirmed command from Today over the demo link. */
    fun command(command: SensorCommand) {
        val engine = engine
        val link = link
        if (engine == null || link?.isOpen != true || run != null || commandRun != null) {
            state.today.commandRefused()
            return
        }
        state.today.commandStarted()
        commandRun = engine.command(link, command) { result ->
            commandRun = null
            state.today.commandFinished(result)
        }
    }

    // The routes of [mainViewController]. The demo's peripheral id or hardware id goes here, every
    // other id to Swift unchanged. Opening one side closes the other, as Swift holds one Bluetooth link.

    fun opening(real: (String) -> Unit, closeReal: () -> Unit): (String) -> Unit = { id ->
        if (owns(id)) {
            closeReal()
            open()
        } else {
            close()
            real(id)
        }
    }

    fun closing(real: (String) -> Unit): (String) -> Unit = { id -> if (owns(id)) close() else real(id) }

    fun closingIdle(real: (String) -> Unit): (String) -> Unit = { id -> if (owns(id)) closeIdle() else real(id) }

    fun closingAll(real: () -> Unit): () -> Unit = {
        close()
        real()
    }

    fun syncing(real: (String) -> Unit): (String) -> Unit = { id -> if (owns(id)) sync(null) else real(id) }

    fun syncingOne(real: (String, String) -> Unit): (String, String) -> Unit = { id, name -> if (owns(id)) sync(name) else real(id, name) }

    fun commanding(real: (String, SensorCommand) -> Unit): (String, SensorCommand) -> Unit = { id, command ->
        if (owns(id)) command(command) else real(id, command)
    }

    fun linking(real: (String) -> IosLink?): (String) -> IosLink? = { device -> linkFor(device) ?: real(device) }

    /**
     * Writes each request to the board in order and offers its reply frames to the link. The
     * board answers asynchronously, like the firmware's storage worker.
     */
    private class DemoPort(private val sensor: DemoSensor, scope: CoroutineScope) : IosLinkPort {
        private val writes = Channel<Pair<ByteArray, (Boolean) -> Unit>>(Channel.UNLIMITED)
        private var replies: IosLink? = null

        init {
            scope.launch {
                try {
                    for ((frame, done) in writes) {
                        done(
                            try {
                                sensor.writeControl(frame)
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                false
                            },
                        )
                    }
                } finally {
                    writes.close()
                    while (true) writes.tryReceive().getOrNull()?.second?.invoke(false) ?: break
                }
            }
            scope.launch { for (frame in sensor.responseFrames) replies?.offer(frame.toNSData()) }
        }

        override val mtu: Int get() = sensor.mtu

        override fun send(frame: NSData, done: (Boolean) -> Unit) {
            if (writes.trySend(frame.toByteArray() to done).isFailure) done(false)
        }

        // The board's reply channel is unbounded, so there is nothing to pause.
        override fun setReadPaused(paused: Boolean) = Unit

        fun attach(link: IosLink) {
            replies = link
        }
    }

    companion object {
        /** A fixed CoreBluetooth style id, so the shared pages take it for a peripheral. */
        const val PERIPHERAL = "FA4E0000-0000-0000-0000-000000000001"

        /** The My devices action, a debug only label in English. */
        const val LABEL = "Demo sensor"

        /** True in a debug binary only, so a release build never offers or opens the demo. */
        val enabled: Boolean get() = Platform.isDebugBinary

        /** The app's demo, set by [mainViewController] in a debug binary. Main thread only. */
        var app: IosDemo? = null

        /** The app's collections and runner, set when [SensorSync.collections] makes them. */
        private var runs: SensorCollections? = null
        private var engine: SensorSync? = null

        /** The demo for this app's [mainViewController], or null in a release binary. */
        fun forApp(state: CompanionState, devices: DevicesBridge): IosDemo? =
            if (enabled) IosDemo(state, devices::sensorOpened).also { app = it } else null

        /**
         * The collections [SensorSync] made, with the demo's open link lent before Swift's. The demo
         * never dials, so a retry with no open demo link is refused. A release binary keeps Swift's.
         */
        fun collections(
            sync: SensorSync,
            make: (pick: (String, LinkPreference) -> IosLink?, dial: (String, LinkPreference, (DialResult) -> Unit) -> Unit) -> SensorCollections,
            pick: (String, LinkPreference) -> IosLink?,
            dial: (String, LinkPreference, (DialResult) -> Unit) -> Unit,
        ): SensorCollections {
            if (!enabled) return make(pick, dial)
            return make(
                { device, prefer -> app?.linkFor(device) ?: pick(device, prefer) },
                { device, prefer, done -> if (app?.ownsDevice(device) == true) done(DialResult.REFUSED) else dial(device, prefer, done) },
            ).also {
                runs = it
                engine = sync
            }
        }
    }
}
