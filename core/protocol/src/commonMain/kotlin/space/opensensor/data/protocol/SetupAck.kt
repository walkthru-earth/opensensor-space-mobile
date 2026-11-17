package space.opensensor.data.protocol

/**
 * The setup record on GET_CONFIG page 1, `thing.setup_ack` and `thing.setup_v`.
 *
 * Bit `i` of `setup_ack` is set once step `i` of the template `firmware.setup` is done. `setup_v`
 * names the step list those bits refer to, as [version] of the ordered step ids. A `setup_v` that
 * differs from the phone's [version] reads as no step done, so a template that reorders, adds or
 * removes a step starts its steps over. The device only stores both numbers, the phone computes
 * them and sends them together with [ConfigPatch.Builder.setupAck].
 *
 * The record never proves a required step. Location and the advert `setup` bit stay derived from
 * the config.
 */
object SetupAck {
    /** The most steps a list may hold, one bit each in a positive 32 bit integer. */
    const val MAX_STEPS = 31

    /** The largest `setup_ack` and `setup_v`, so both fit a signed 32 bit JSON integer. */
    const val MASK_MAX = Int.MAX_VALUE

    /**
     * The `setup_v` of [stepIds], the ordered `firmware.setup` ids. The first 31 bits of SHA-256
     * over the ids joined by `\n` in UTF-8, read big endian. Never 0, so 0 and absent both read as
     * a record that names no list. Only the ids count, so a step that only changes `req` or `keys`
     * keeps its bit.
     */
    fun version(stepIds: List<String>): Int {
        val bytes = stepIds.joinToString("\n").encodeToByteArray()
        val d = Sha256().apply { update(bytes, 0, bytes.size) }.digest()
        val v = ((d[0].toInt() and 0x7F) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or
            ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        return if (v == 0) 1 else v
    }

    /** The done bits of [thing] for the step list of [expected] version, or 0 when the record names another list. */
    fun done(thing: ThingConfig?, expected: Int): Int {
        val ack = thing?.setupAck ?: return 0
        return if (thing.setupV == expected && ack >= 0) ack else 0
    }

    /** Whether step [index] is done in [thing] for the step list of [expected] version. */
    fun isDone(thing: ThingConfig?, expected: Int, index: Int): Boolean {
        require(index in 0 until MAX_STEPS) { "a step list holds at most $MAX_STEPS steps" }
        return (done(thing, expected) and (1 shl index)) != 0
    }

    /**
     * The `setup_ack` to send once step [index] is done. It keeps the other bits only while [thing]
     * names the same list, so a stale record starts from no step done.
     */
    fun withStep(thing: ThingConfig?, expected: Int, index: Int): Int {
        require(index in 0 until MAX_STEPS) { "a step list holds at most $MAX_STEPS steps" }
        return done(thing, expected) or (1 shl index)
    }
}
