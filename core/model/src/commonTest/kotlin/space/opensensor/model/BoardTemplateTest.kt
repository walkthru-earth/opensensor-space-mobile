package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoardTemplateTest {
    private val cores3 = BoardTemplate.parse(CORES3_TEMPLATE, desc = DESC_A)

    @Test
    fun parsesTheCoreS3TemplateHeader() {
        assertEquals(TemplateKey(CORES3_DICT, DESC_A), cores3.key)
        assertEquals("cores3-telemetry-v5", cores3.id)
        assertEquals("cores3-telemetry-v5", cores3.schema)
        assertEquals(3, cores3.protoMin)
        assertEquals(10.0, cores3.defaultSampleIntervalS)
        assertEquals(listOf("pmsa003", "m5_imu", "m5_power"), cores3.instruments)
        assertEquals("event_time_utc_us", cores3.primaryDatetime)
        assertTrue(cores3.dictionaryHref!!.endsWith("telemetry_fields.inc"))
        assertEquals(11, cores3.datastreams.size)
    }

    @Test
    fun parsesTheCoreS3MeasurementColumn() {
        val pm25 = cores3.datastream("pm25_atmospheric_ug_m3")!!
        assertEquals("int32", pm25.type)
        assertTrue(pm25.isInteger)
        assertEquals(Role.PHYSICAL_MEASUREMENT, pm25.role)
        assertEquals("ug/m3", pm25.unit)
        assertEquals("http://vocab.nerc.ac.uk/collection/P07/current/YUDG9DXK/", pm25.property?.id)
        assertEquals("mass_concentration_of_pm2p5_ambient_aerosol_particles_in_air", pm25.property?.standardName)
        assertEquals(Sensor("pmsa003", board = "cores3-telemetry-v5"), pm25.sensor)
        assertEquals(17, pm25.fieldId)
        assertEquals(ValidityGate.Equals("pms_status", GateValue.Number(4.0)), pm25.validWhen)
        assertEquals(180.0, pm25.settleSeconds)
        assertEquals(Aggregate.MEAN, pm25.aggregate)
        assertEquals(PlausibleRange(0.0, 1000.0), pm25.plausibleRange)
        assertEquals("pm25", pm25.liveKey)
        assertNull(pm25.unitLabel)
        assertEquals(listOf("pm25_atmospheric_ug_m3"), pm25.sourceColumns)
        assertFalse(pm25.isDerived)
    }

    @Test
    fun parsesCodesGatesAndUnitLabels() {
        val status = cores3.datastream("pms_status")!!
        assertEquals(Role.QUALITY_INFORMATION, status.role)
        assertEquals(Aggregate.MODE, status.aggregate)
        assertEquals(5, status.classes.size)
        assertTrue(status.classOf(0)!!.nodata)
        assertEquals("valid", status.classOf(4)?.name)
        assertNull(status.classOf(9))
        assertEquals("code", status.unitLabel)

        val imu = cores3.datastream("imu_temperature_c")!!
        assertEquals(ValidityGate.Finite("imu_temperature_c"), imu.validWhen)
        assertEquals("imu_t", imu.liveKey)
        assertEquals("degC", imu.unitLabel)
        assertFalse(imu.isInteger)

        val accel = cores3.datastream("accel_z_g")!!
        assertNull(accel.plausibleRange)
        assertNull(accel.liveKey)

        assertEquals(Aggregate.POINT, cores3.datastream("battery_percent")!!.aggregate)
        assertEquals(Role.COORDINATE, cores3.datastream("event_time_utc_us")!!.role)
    }

    @Test
    fun mapsLiveKeysToColumns() {
        assertEquals("pm25_atmospheric_ug_m3", cores3.columnForLiveKey("pm25"))
        assertEquals("battery_percent", cores3.columnForLiveKey("pct"))
        assertNull(cores3.columnForLiveKey("t"))
    }

    @Test
    fun propertiesAreBoardNeutralAndSensorsCarryTheBoard() {
        val pm1 = cores3.datastream("pm1_atmospheric_ug_m3")!!
        assertEquals(
            "http://vocab.nerc.ac.uk/standard_name/mass_concentration_of_pm1_ambient_aerosol_particles_in_air/",
            pm1.property?.id,
        )
        assertEquals("urn:walkthru-earth:property:pm25_cf1_mass_concentration", cores3.datastream("pm25_cf1_ug_m3")!!.property?.id)
        assertEquals(Sensor("pmsa003", "cores3-telemetry-v5"), pm1.sensor)
        // The same quantity on another board shares the property, the sensor tells them apart.
        val other = BoardTemplate.parse(
            template(
                """{"name": "pm1", "type": "float", "roles": ["physicalMeasurement"],
                    "opensensor:property": "${pm1.property!!.id}",
                    "opensensor:procedure": "pmsa003"}""",
            ),
        ).datastream("pm1")!!
        assertEquals(pm1.property?.id, other.property?.id)
        assertTrue(pm1.sensor != other.sensor)
    }

    @Test
    fun columnWithoutPropertyOrProcedureHasNeither() {
        val t = BoardTemplate.parse(template("""{"name": "x", "type": "float"}"""))
        assertNull(t.datastream("x")!!.sensor)
        assertNull(t.datastream("x")!!.property)
        val empty = BoardTemplate.parse(template("""{"name": "y", "type": "float", "opensensor:property": ""}"""))
        assertNull(empty.datastream("y")!!.property)
    }

    @Test
    fun auxiliaryColumnsAreKept() {
        assertEquals(Role.AUXILIARY_INFORMATION, cores3.datastream("imu_temperature_c")!!.role)
        assertEquals(Role.AUXILIARY_INFORMATION, cores3.datastream("battery_percent")!!.role)
    }

    @Test
    fun templateIndexResolvesByDescThenDict() {
        val bundled = BoardTemplate.parse(CORES3_TEMPLATE)
        val served = BoardTemplate.parse(CORES3_TEMPLATE, desc = DESC_A)
        val index = TemplateIndex(bundled = listOf(bundled), served = listOf(served))
        assertSame(served, index.resolve(CORES3_DICT, DESC_A))
        // An unknown desc falls back to the bundled template of the same dictionary.
        assertSame(bundled, index.resolve(CORES3_DICT, DESC_B))
        assertSame(bundled, index.resolve(CORES3_DICT))
        assertNull(index.resolve(OTHER_DICT, DESC_A))
        // A served template is never the dict fallback.
        assertNull(TemplateIndex(bundled = emptyList(), served = listOf(served)).resolve(CORES3_DICT, DESC_B))
    }

    @Test
    fun bundledTemplateWithADescIsStillTheDictFallback() {
        val bundled = BoardTemplate.parse(CORES3_TEMPLATE, desc = DESC_A)
        val index = TemplateIndex(bundled = listOf(bundled))
        assertSame(bundled, index.resolve(CORES3_DICT, DESC_A))
        assertSame(bundled, index.resolve(CORES3_DICT, DESC_B))
        assertSame(bundled, index.resolve(CORES3_DICT))
        assertFailsWith<IllegalArgumentException> {
            TemplateIndex(bundled = listOf(bundled, BoardTemplate.parse(CORES3_TEMPLATE)))
        }
    }

    @Test
    fun unknownAggregateParsesAndRollsUpAsPoint() {
        val t = BoardTemplate.parse(
            template("""{"name": "noise_db", "type": "float", "unit": "dB", "roles": ["physicalMeasurement"], "opensensor:aggregate": "median"}"""),
        )
        val noise = t.datastream("noise_db")!!
        assertEquals("median", noise.aggregate?.id)
        assertFalse(noise.aggregate!!.isKnown)
        assertEquals(Aggregate.POINT, noise.rollup)
    }

    @Test
    fun missingAggregateRollsUpAsPoint() {
        val t = BoardTemplate.parse(template("""{"name": "x", "type": "float", "roles": ["physicalMeasurement"]}"""))
        assertNull(t.datastream("x")!!.aggregate)
        assertEquals(Aggregate.POINT, t.datastream("x")!!.rollup)
    }

    @Test
    fun unknownRoleAndFieldsStillParse() {
        val t = BoardTemplate.parse(template("""{"name": "x", "type": "float", "roles": ["image"], "opensensor:future": 1, "other": {}}"""))
        assertEquals(Role("image"), t.datastream("x")!!.role)
        assertFalse(t.datastream("x")!!.role!!.isMeasurement)
    }

    @Test
    fun derivedDatastreamHasItsOwnIdAndSourceColumns() {
        val t = BoardTemplate.parse(
            template(
                """
                {"name": "pm25_cf1_ug_m3", "type": "int32", "unit": "ug/m3", "roles": ["physicalMeasurement"]},
                {"name": "relative_humidity_percent", "type": "float", "unit": "%", "roles": ["physicalMeasurement"]},
                {"name": "pm25_epa", "type": "double", "unit": "ug/m3", "roles": ["modelResult"],
                 "opensensor:aggregate": "mean",
                 "opensensor:derived": {"from": ["pm25_cf1_ug_m3", "relative_humidity_percent"], "method": "https://example.org/epa-correction"}}
                """,
            ),
        )
        val epa = t.datastream("pm25_epa")!!
        assertTrue(epa.isDerived)
        assertTrue(epa.role!!.isMeasurement)
        assertEquals(listOf("pm25_cf1_ug_m3", "relative_humidity_percent"), epa.sourceColumns)
        assertEquals("https://example.org/epa-correction", epa.derived?.method)
    }

    @Test
    fun liveKeyMappingIsPerTemplate() {
        // Two templates for one dictionary send the same column under different LIVE keys.
        val column = """{"name": "imu_temperature_c", "type": "float", "unit": "Cel", "roles": ["auxiliaryInformation"]}"""
        val first = BoardTemplate.parse(template(column, liveKeys = """{"imu_temperature_c": "t"}"""), desc = DESC_B)
        val second = BoardTemplate.parse(template(column, liveKeys = """{"imu_temperature_c": "imu_t"}"""), desc = DESC_A)
        assertEquals("imu_temperature_c", first.columnForLiveKey("t"))
        assertNull(first.columnForLiveKey("imu_t"))
        assertEquals("imu_temperature_c", second.columnForLiveKey("imu_t"))
        // One dictionary, two templates told apart by desc.
        assertEquals(first.key.dict, second.key.dict)
        assertTrue(first.key != second.key)
    }

    @Test
    fun gateEqualsAcceptsStringAndBoolean() {
        val t = BoardTemplate.parse(
            template(
                """
                {"name": "a", "type": "string", "opensensor:valid_when": {"column": "mode", "equals": "run"}},
                {"name": "b", "type": "float", "opensensor:valid_when": {"column": "ok", "equals": true}}
                """,
            ),
        )
        assertEquals(ValidityGate.Equals("mode", GateValue.Text("run")), t.datastream("a")!!.validWhen)
        assertEquals(ValidityGate.Equals("ok", GateValue.Flag(true)), t.datastream("b")!!.validWhen)
    }

    @Test
    fun rejectsNonTemplates() {
        assertFailsWith<IllegalArgumentException> { BoardTemplate.parse("""{"type": "Collection"}""") }
        assertFailsWith<IllegalArgumentException> { BoardTemplate.parse("not json") }
        assertFailsWith<IllegalArgumentException> { BoardTemplate.parse(template("", dict = "abc")) }
        assertFailsWith<IllegalArgumentException> { BoardTemplate.parse(CORES3_TEMPLATE, desc = "XYZ") }
        assertFailsWith<IllegalArgumentException> { BoardTemplate.parse(template("", sampleIntervalS = "0")) }
        assertFailsWith<IllegalArgumentException> {
            BoardTemplate.parse(template("""{"name": "x", "type": "int32"}, {"name": "x", "type": "int32"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            BoardTemplate.parse(template("""{"name": "x", "type": "int32", "opensensor:valid_when": {"column": "y"}}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            BoardTemplate.parse(template("""{"name": "x", "type": "int32", "opensensor:plausible_range": [1]}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            BoardTemplate.parse(template("""{"name": "x", "type": "float", "opensensor:valid_when": {"column": "x", "finite": false}}"""))
        }
    }
}
