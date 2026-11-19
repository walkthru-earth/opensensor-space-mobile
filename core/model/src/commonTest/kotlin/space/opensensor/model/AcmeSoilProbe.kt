package space.opensensor.model

/**
 * A fake board no firmware builds and the app never bundles, `acme-soil-probe-v1`. It carries what a
 * metadata driven app must render without a code change. Soil moisture under a property URN the
 * registry does not list, a status column with classes, a `{bitmask}` column with bit fields, the
 * column display hints, a top level `opensensor:primary` and `opensensor:scales`, and a `firmware`
 * block with `live_keys`, `setup`, `settings` and typed `config_keys`. It also carries fields no
 * profile defines, which a reader ignores. The `firmware` setup fields are the planned shapes of
 * opensensor-space-firmware decision 0003, which the stac-opensensor template schema does not accept yet.
 */
internal const val ACME_DICT = "a5c0ffee00000000000000000000000000000000000000000000000000000001"
internal const val ACME_DESC = "a5c0ffee00000000000000000000000000000000000000000000000000000002"

internal val ACME_SOIL_PROBE_TEMPLATE = """
{
  "type": "OpenSensorTemplate",
  "template_version": "0.1.0",
  "id": "acme-soil-probe-v1",
  "title": "Acme soil probe",
  "keywords": ["soil", "moisture"],
  "instruments": ["acme_soil"],
  "opensensor:sample_interval_s": 60,
  "opensensor:primary": "soil_moisture_pct",
  "opensensor:scales": {
    "acme-soil-dryness": {
      "source": "https://example.org/acme/soil-dryness",
      "unit": "%",
      "period": "PT1H",
      "steps": [
        {"upper": 10, "label": {"en": "Dry", "ar": "جاف"}, "color_hint": "C8783C"},
        {"upper": 40, "label": "Moist", "color_hint": "6FA85A"},
        {"upper": null, "label": {"en": "Wet"}, "color_hint": "3C78C8"}
      ]
    }
  },
  "acme:future_top_level": {"anything": [1, 2, 3]},
  "item_assets": {
    "data": {
      "type": "application/vnd.apache.parquet",
      "roles": ["data"],
      "table:primary_datetime": "event_time_utc_us",
      "table:columns": [
        {
          "name": "device_id",
          "type": "int64",
          "roles": ["referenceInformation"],
          "opensensor:procedure": "identity",
          "opensensor:field_id": 2,
          "opensensor:group": "device"
        },
        {
          "name": "event_time_utc_us",
          "type": "int64",
          "unit": "us",
          "roles": ["coordinate"],
          "opensensor:property": "http://vocab.nerc.ac.uk/collection/P07/current/CFSN0115/",
          "opensensor:procedure": "clock",
          "opensensor:field_id": 9
        },
        {
          "name": "probe_status",
          "type": "int32",
          "roles": ["qualityInformation"],
          "opensensor:procedure": "acme_soil",
          "opensensor:field_id": 20,
          "opensensor:aggregate": "mode",
          "classification:classes": [
            {"value": 0, "name": "missing", "nodata": true},
            {"value": 1, "name": "valid", "title": "Reading", "color_hint": "2E7D32"},
            {"value": 2, "name": "dry-probe", "title": "Probe out of soil", "description": "The probe reads air, push it back in."}
          ]
        },
        {
          "name": "probe_flags",
          "type": "uint32",
          "unit": "{bitmask}",
          "roles": ["qualityInformation"],
          "opensensor:procedure": "acme_soil",
          "opensensor:field_id": 21,
          "classification:bitfields": [
            {
              "offset": 0,
              "length": 1,
              "name": "probe-disconnected",
              "description": "The probe cable is unplugged.",
              "classes": [{"value": 0, "name": "ok"}, {"value": 1, "name": "set"}]
            },
            {
              "offset": 4,
              "length": 2,
              "name": "battery-level",
              "classes": [
                {"value": 0, "name": "empty"},
                {"value": 1, "name": "low"},
                {"value": 2, "name": "half"},
                {"value": 3, "name": "full"}
              ]
            }
          ]
        },
        {
          "name": "soil_moisture_pct",
          "type": "float",
          "title": "Soil moisture",
          "description": "Water in the soil as a share of its volume.",
          "unit": "%",
          "roles": ["physicalMeasurement"],
          "opensensor:property": "urn:walkthru-earth:property:acme_soil_volumetric_water_content",
          "opensensor:procedure": "acme_soil",
          "opensensor:field_id": 22,
          "opensensor:valid_when": {"column": "probe_status", "equals": 1},
          "opensensor:aggregate": "mean",
          "opensensor:plausible_range": [0, 100],
          "opensensor:title_i18n": {"ar": "رطوبة التربة", "fr": "Humidité du sol"},
          "opensensor:description_i18n": {"ar": "الماء في التربة كنسبة من حجمها."},
          "opensensor:decimals": 1,
          "opensensor:group": "climate",
          "opensensor:scale": "acme-soil-dryness",
          "acme:future_column_field": true
        },
        {
          "name": "soil_temperature_c",
          "type": "float",
          "unit": "Cel",
          "roles": ["physicalMeasurement"],
          "opensensor:property": "urn:walkthru-earth:property:acme_soil_temperature",
          "opensensor:procedure": "acme_soil",
          "opensensor:field_id": 23,
          "opensensor:valid_when": {"column": "probe_status", "equals": 1},
          "opensensor:aggregate": "mean"
        },
        {
          "name": "battery_mv",
          "type": "int32",
          "unit": "mV",
          "roles": ["auxiliaryInformation"],
          "opensensor:procedure": "acme_power",
          "opensensor:field_id": 24,
          "opensensor:aggregate": "minimum",
          "opensensor:group": "power",
          "opensensor:decimals": 0
        }
      ]
    }
  },
  "links": [
    {"rel": "describedby", "href": "https://example.org/acme/telemetry_fields.inc", "type": "text/plain", "file:checksum": "1220$ACME_DICT"}
  ],
  "firmware": {
    "schema": "acme-soil-probe-v1",
    "proto_min": 3,
    "dict": "$ACME_DICT",
    "live_keys": {"soil_moisture_pct": "sm", "soil_temperature_c": "st", "probe_status": "ps"},
    "unit_labels": {"battery_mv": "mV"},
    "setup": [
      {"id": "name", "req": false},
      {"id": "location", "req": true},
      {"id": "wifi"},
      {"id": "probe", "req": true, "keys": ["probe.depth_cm", "probe.mode", "probe.not_declared"]},
      {"id": "acme-mystery", "req": true},
      {"req": true},
      "not a step"
    ],
    "settings": [
      {"id": "location"},
      {"id": "probe", "keys": ["probe.depth_cm", "probe.mode", "probe.calibrate"]},
      {"id": ""}
    ],
    "config_keys": {
      "probe.depth_cm": {"type": "integer", "values": {"min": 5, "max": 60}, "applies": "next sample", "page": 7, "label": "Probe depth", "label_i18n": {"ar": "عمق المجس"}},
      "probe.mode": {"type": "enum", "values": ["fast", "slow"], "applies": "next boot", "page": 7, "label": "Mode"},
      "probe.calibrate": {"type": "boolean", "applies": "action", "page": 7, "label": "Calibrate now"},
      "probe.future": {"type": "acme-color", "applies": "on full moon", "values": "unreadable", "page": "seven"},
      "probe.no_type": {"applies": "immediately", "label": "No type"}
    },
    "acme:future_firmware_field": 1
  }
}
"""
