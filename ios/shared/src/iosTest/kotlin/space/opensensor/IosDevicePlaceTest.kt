@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package space.opensensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import platform.Foundation.NSData
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.sync.ThingPlace
import space.opensensor.data.sync.ThingPlaces
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Device settings keeps the sensor's Discover pin in step with the location page it reads, on a read
 * and on a save (#226). The Thing is the one the known record names for this peripheral.
 */
class IosDevicePlaceTest {
    private val address = "synthetic-peripheral"
    private val thing = "synthetic-thing-1"
    private val cell = "85283473fffffff"

    private class RecordingPort : IosLinkPort {
        val sent = mutableListOf<Int>()
        override val mtu = 517
        override fun send(frame: NSData, done: (Boolean) -> Unit) {
            sent += frame.toByteArray()[0].toInt() and 0xFF
            done(true)
        }
        override fun setReadPaused(paused: Boolean) = Unit
    }

    private class NoRecords : KnownRecordStorage {
        override fun load(): String? = null
        override fun save(text: String) = Unit
    }

    /** [ThingPlaces] in memory. */
    private class MemoryThingPlaces : ThingPlaces {
        private val state = MutableStateFlow<List<ThingPlace>>(emptyList())
        val all: List<ThingPlace> get() = state.value
        override fun observeAll(): Flow<List<ThingPlace>> = state
        override suspend fun put(place: ThingPlace) = state.update { list -> list.filter { it.thing != place.thing } + place }
        override suspend fun remove(thing: String) = state.update { list -> list.filter { it.thing != thing } }
    }

    private fun IosLink.reply(frame: Frame) = offer(FrameCodec.encode(frame).toNSData())

    private fun page(cell: String, mobility: String) =
        """{"location":{"cell":"$cell","resolution":8,"cell_resolution":8,"lat":null,"lon":null,"country":""},"thing":{"mobility":"$mobility"}}"""

    /** Device settings for a known sensor open over Bluetooth through [link]. */
    private fun TestScope.settings(link: IosLink, places: ThingPlaces): IosDeviceConfigSource {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val state = CompanionState().apply {
            connectionStarted(address)
            check(receiveInfo("""{"proto":3,"dev":"aabbccddeeff"}"""))
        }
        val devices = DevicesBridge(state, emptyFlow(), NoRecords(), { 0L }, {}, {}, {}, { _, _ -> }, { _, _ -> })
        check(devices.sensorOpened(address, "Kitchen", thing, "aabbccddeeff").accepted)
        val links = DeviceConfigLinks(
            openConfigLink = { link },
            openBluetoothLink = { link },
            saveWifiAccess = {},
            forgetWifiAccess = {},
            connect = {},
            connectBluetooth = {},
            reopen = {},
            disconnect = {},
            scanLan = {},
            scanSticker = null,
        )
        return IosDeviceConfigSource(address, state, devices, links, CoroutineScope(backgroundScope.coroutineContext + dispatcher), places)
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun aReadAndASaveKeepThePinAndAMovingPageDropsIt() = runTest {
        val port = RecordingPort()
        val link = IosLink(port, false, "synthetic-place-test", StandardTestDispatcher(testScheduler), StandardTestDispatcher(testScheduler))
        val places = MemoryThingPlaces()
        val source = settings(link, places)
        try {
            val reading = async { source.readLocation() }
            runCurrent()
            assertEquals(listOf(Opcode.GET_CONFIG), port.sent)
            link.reply(Frame.Config(0, page(cell, "fixed")))
            runCurrent()
            assertEquals(cell, reading.await()?.cell)
            assertEquals(listOf(thing to cell), places.all.map { it.thing to it.cell }, "a read records the pin")

            val moving = async { source.writeLocation(ConfigPatch.build { location(cell, 8, "") }) }
            runCurrent()
            link.reply(Frame.Config(0, "{}"))
            runCurrent()
            link.reply(Frame.Config(0, page(cell, "moving")))
            runCurrent()
            moving.await()
            assertTrue(places.all.isEmpty(), "a moving page drops the pin")

            val fixed = async { source.writeLocation(ConfigPatch.build { location(cell, 8, "") }) }
            runCurrent()
            link.reply(Frame.Config(0, "{}"))
            runCurrent()
            link.reply(Frame.Config(0, page(cell, "fixed")))
            runCurrent()
            assertEquals(cell, fixed.await()?.cell)
            assertEquals(listOf(thing to cell), places.all.map { it.thing to it.cell }, "a save records the pin")
            assertEquals(listOf(Opcode.GET_CONFIG, Opcode.SET_CONFIG, Opcode.GET_CONFIG, Opcode.SET_CONFIG, Opcode.GET_CONFIG), port.sent)
        } finally {
            link.close()
            runCurrent()
        }
    }
}
