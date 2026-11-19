package space.opensensor.data.lan

import space.opensensor.data.lan.LanHandshake.Verdict
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.LanProtocol
import space.opensensor.link.Heartbeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every verdict both phones read from the frame that answers the LAN handshake. */
class LanHandshakeTest {
    private val dev = "a1b2c3d4e5f6"
    private val thing = "thing-1"

    private fun info(proto: Int? = 3, thing: String? = this.thing, dev: String? = this.dev): String = buildList {
        proto?.let { add("\"proto\":$it") }
        thing?.let { add("\"thing\":\"$it\"") }
        dev?.let { add("\"dev\":\"$it\"") }
    }.joinToString(",", "{", "}")

    private fun hello(proto: Int = 3, payloadMax: Int = LanProtocol.PAYLOAD_MAX, json: String = info()) = Frame.Hello(proto, payloadMax, json)

    @Test
    fun acceptedHelloCarriesTheParsedInfo() {
        val verdict = assertIs<Verdict.Accepted>(LanHandshake.verify(hello(), savedDevice = dev, savedThing = thing))
        assertEquals(thing, verdict.info.thing)
        assertEquals(dev, verdict.info.dev)
        assertEquals(LanProtocol.PAYLOAD_MAX, verdict.hello.payloadMax)
    }

    @Test
    fun hardwareIdsCompareWithoutCaseAsTheContinuityPolicyDoes() {
        assertIs<Verdict.Accepted>(LanHandshake.verify(hello(json = info(dev = dev.uppercase())), savedDevice = dev, savedThing = thing))
    }

    @Test
    fun anythingButHelloIsTheWrongKind() {
        assertEquals(Verdict.WrongKind, LanHandshake.check(Frame.Closed(1)))
        assertEquals(Verdict.WrongKind, LanHandshake.verify(Frame.StatusPush("{}"), savedDevice = dev, savedThing = thing))
    }

    @Test
    fun aHelloOrInfoBelowProto3NeedsAFirmwareUpdate() {
        assertEquals(Verdict.OldProto(2), LanHandshake.check(hello(proto = 2)))
        assertEquals(Verdict.OldProto(2), LanHandshake.check(hello(json = info(proto = 2))))
        assertEquals(Verdict.OldProto(null), LanHandshake.check(hello(json = info(proto = null))))
    }

    @Test
    fun payloadCapOutsideTheContractRangeIsRefused() {
        assertEquals(Verdict.PayloadCapOutOfRange(LanHandshake.MIN_PAYLOAD_MAX - 1), LanHandshake.check(hello(payloadMax = LanHandshake.MIN_PAYLOAD_MAX - 1)))
        assertEquals(Verdict.PayloadCapOutOfRange(LanProtocol.PAYLOAD_MAX + 1), LanHandshake.check(hello(payloadMax = LanProtocol.PAYLOAD_MAX + 1)))
        assertIs<Verdict.Accepted>(LanHandshake.check(hello(payloadMax = LanHandshake.MIN_PAYLOAD_MAX)))
    }

    @Test
    fun unreadableOrIncompleteInfoIsBadInfo() {
        assertEquals(Verdict.BadInfo, LanHandshake.check(hello(json = "not json")))
        assertEquals(Verdict.BadInfo, LanHandshake.verify(hello(json = info(thing = null)), savedDevice = dev, savedThing = thing))
        assertEquals(Verdict.BadInfo, LanHandshake.verify(hello(json = info(thing = "..")), savedDevice = dev, savedThing = thing))
        assertEquals(Verdict.BadInfo, LanHandshake.verify(hello(json = info(dev = "zz")), savedDevice = dev, savedThing = thing))
    }

    @Test
    fun aHelloWithoutTheSavedHardwareIdIsADeviceChange() {
        assertEquals(Verdict.DeviceChanged, LanHandshake.verify(hello(json = info(dev = "0000000000ff")), savedDevice = dev, savedThing = thing))
        assertEquals(Verdict.DeviceChanged, LanHandshake.verify(hello(json = info(dev = null)), savedDevice = dev, savedThing = thing))
    }

    @Test
    fun aNewThingOverWiFiIsAThingChange() {
        assertEquals(Verdict.ThingChanged, LanHandshake.verify(hello(json = info(thing = "thing-2")), savedDevice = dev, savedThing = thing))
    }

    @Test
    fun bothPhonesShareOneSetOfLimits() {
        assertEquals(LanProtocol.PAYLOAD_MAX, LanHandshake.PAYLOAD_MAX)
        assertEquals(Heartbeat.LAN.pingAfterIdle.inWholeMilliseconds, LanProtocol.KEEPALIVE_AFTER_IDLE_MS)
        assertTrue(Heartbeat.LAN.deadline.inWholeMilliseconds < LanProtocol.DEVICE_IDLE_LIMIT_MS)
    }
}
