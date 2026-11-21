package space.opensensor.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import space.opensensor.data.ble.DeviceErrorException
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.data.protocol.ThingMobility
import space.opensensor.model.BundledCatalog
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The demo board through the same link driver and typed session both phones use. */
class DemoSensorTest {

    private fun TestScope.sensor(simulateLan: Boolean = false): DemoSensor = DemoSensor(
        address = if (simulateLan) DemoSensor.LAN_ADDRESS else DemoSensor.ADDRESS,
        simulateLan = simulateLan,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private suspend fun open(sensor: DemoSensor, scope: CoroutineScope): DeviceSession =
        DeviceSession.over(sensor, scope).also { it.link.open() }

    @Test
    fun infoNamesTheBundledTemplate() = runTest {
        val demo = sensor()
        val session = open(demo, backgroundScope)
        val info = session.readInfo()
        val template = assertNotNull(BundledCatalog.template(assertNotNull(info.dict)))
        assertEquals(template.schema, info.schema)
        assertEquals(3, info.proto)
        assertEquals(demo.thing, info.thing)
        assertEquals(TransportKind.FAKE, demo.kind)
    }

    @Test
    fun listsAndDownloadsAFileWithItsCrc() = runTest {
        val demo = sensor()
        val session = open(demo, backgroundScope)
        val (files, end) = session.list()
        assertEquals(demo.files.size, files.size)
        assertEquals(files.size.toLong(), end.count)

        val wanted = demo.files.minBy { it.bytes.size }
        val opened = session.open(wanted.name)
        assertEquals(wanted.crc32, opened.crc32)
        val received = ArrayList<Byte>()
        var offset = 0L
        while (offset < opened.size) {
            val readEnd = session.read(opened.handle, offset, demo.maxRead.toLong()) { chunk -> received += chunk.payload.toList() }
            offset = readEnd.nextOffset
        }
        session.close(opened.handle)
        val bytes = received.toByteArray()
        assertContentEquals(wanted.bytes, bytes)
        assertEquals(opened.crc32, ParquetVerify.crc32(bytes))
        assertTrue(ParquetVerify.hasMagic(bytes))
    }

    @Test
    fun liveCarriesEveryTemplateKeyInsideItsRange() = runTest {
        val demo = sensor()
        val session = open(demo, backgroundScope)
        val sample = session.live.first()
        val template = demo.template
        for ((column, key) in template.liveKeys) {
            if (key in LiveSample.HEADER) continue
            assertTrue(sample.has(key), "LIVE has no $key")
            val value = assertNotNull(sample[key], "LIVE $key is null")
            val stream = assertNotNull(template.datastream(column))
            stream.plausibleRange?.let { assertTrue(value in it, "$key $value outside ${it.min}..${it.max}") }
            if (stream.classes.isNotEmpty()) assertTrue(stream.classes.any { it.value.toDouble() == value && !it.nodata }, "$key $value is no class")
        }
        assertNotNull(sample.seq)
        assertNotNull(sample.mono)
    }

    @Test
    fun liveValuesPassTheirTemplateGates() = runTest {
        val demo = sensor()
        val session = open(demo, backgroundScope)
        val sample = session.live.first()
        val template = demo.template
        for ((column, key) in template.liveKeys) {
            val gate = template.datastream(column)?.validWhen ?: continue
            val gateKey = template.liveKeys[gate.column] ?: continue
            assertTrue(gate.accepts(sample[gateKey]), "$key is gated off by ${gate.column}")
        }
    }

    @Test
    fun setConfigAcceptsKnownKeysAndRefusesUnknownOnes() = runTest {
        val demo = sensor()
        val session = open(demo, backgroundScope)
        session.setConfig(ConfigPatch.build { thingMobility(ThingMobility.MOVING) })
        assertEquals("moving", demo.mobility)

        // One key it does not know refuses the whole patch, like the firmware, so nothing applies.
        val refused = assertFailsWith<DeviceErrorException> {
            session.setConfig(ConfigPatch.build { thingMobility(ThingMobility.FIXED).sensorIdentity("acme", "x1", "42", false) })
        }
        assertEquals(ProtocolError.INVALID_CONFIG, refused.protocolError)
        assertEquals("moving", demo.mobility)
    }

    @Test
    fun theLanVariantRefusesTheToken() = runTest {
        val demo = sensor(simulateLan = true)
        val session = open(demo, backgroundScope)
        assertEquals(TransportKind.LAN, demo.kind)
        val refused = assertFailsWith<DeviceErrorException> { session.getToken() }
        assertEquals(ProtocolError.NOT_ALLOWED_ON_LINK, refused.protocolError)
    }

    @Test
    fun theBluetoothVariantHandsOutAToken() = runTest {
        val session = open(sensor(), backgroundScope)
        assertEquals(32, session.getToken().token.size)
    }

    @Test
    fun addressesAreKnownAsDemo() {
        assertTrue(DemoSensor.isDemo(DemoSensor.ADDRESS))
        assertTrue(DemoSensor.isDemo(DemoSensor.LAN_ADDRESS))
        assertFalse(DemoSensor.isDemo("AA:BB:CC:DD:EE:FF"))
    }
}
