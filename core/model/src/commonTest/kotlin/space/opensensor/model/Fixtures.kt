package space.opensensor.model

/** `templates/cores3-telemetry-v5.json` from walkthru-earth/stac-opensensor main at a017edf, verbatim. */
internal val CORES3_TEMPLATE = """
{
  "type": "OpenSensorTemplate",
  "template_version": "0.1.0",
  "id": "cores3-telemetry-v5",
  "title": "M5Stack CoreS3 with a Plantower PMSA003",
  "keywords": [
    "air quality",
    "PM2.5",
    "low-cost sensor"
  ],
  "instruments": [
    "pmsa003",
    "m5_imu",
    "m5_power"
  ],
  "opensensor:sample_interval_s": 10,
  "item_assets": {
    "data": {
      "type": "application/vnd.apache.parquet",
      "roles": [
        "data"
      ],
      "table:primary_datetime": "event_time_utc_us",
      "table:columns": [
        {
          "name": "device_id",
          "type": "int64",
          "roles": [
            "referenceInformation"
          ],
          "opensensor:procedure": "identity",
          "opensensor:field_id": 2
        },
        {
          "name": "event_time_utc_us",
          "type": "int64",
          "unit": "us",
          "roles": [
            "coordinate"
          ],
          "opensensor:procedure": "clock",
          "opensensor:field_id": 9
        },
        {
          "name": "pms_status",
          "type": "int32",
          "roles": [
            "qualityInformation"
          ],
          "classification:classes": [
            {
              "value": 0,
              "name": "missing",
              "nodata": true
            },
            {
              "value": 1,
              "name": "warming"
            },
            {
              "value": 2,
              "name": "stale"
            },
            {
              "value": 3,
              "name": "sensor-error"
            },
            {
              "value": 4,
              "name": "valid"
            }
          ],
          "opensensor:procedure": "pmsa003",
          "opensensor:field_id": 11,
          "opensensor:aggregate": "mode"
        },
        {
          "name": "pm1_atmospheric_ug_m3",
          "type": "int32",
          "unit": "ug/m3",
          "roles": [
            "physicalMeasurement"
          ],
          "cf:standard_name": "mass_concentration_of_pm1_ambient_aerosol_particles_in_air",
          "opensensor:property": "http://vocab.nerc.ac.uk/standard_name/mass_concentration_of_pm1_ambient_aerosol_particles_in_air/",
          "opensensor:procedure": "pmsa003",
          "opensensor:field_id": 16,
          "opensensor:valid_when": {
            "column": "pms_status",
            "equals": 4
          },
          "opensensor:settle_s": 180,
          "opensensor:aggregate": "mean",
          "opensensor:plausible_range": [
            0,
            1000
          ]
        },
        {
          "name": "pm25_atmospheric_ug_m3",
          "type": "int32",
          "unit": "ug/m3",
          "roles": [
            "physicalMeasurement"
          ],
          "cf:standard_name": "mass_concentration_of_pm2p5_ambient_aerosol_particles_in_air",
          "opensensor:property": "http://vocab.nerc.ac.uk/collection/P07/current/YUDG9DXK/",
          "opensensor:procedure": "pmsa003",
          "opensensor:field_id": 17,
          "opensensor:valid_when": {
            "column": "pms_status",
            "equals": 4
          },
          "opensensor:settle_s": 180,
          "opensensor:aggregate": "mean",
          "opensensor:plausible_range": [
            0,
            1000
          ]
        },
        {
          "name": "pm10_atmospheric_ug_m3",
          "type": "int32",
          "unit": "ug/m3",
          "roles": [
            "physicalMeasurement"
          ],
          "cf:standard_name": "mass_concentration_of_pm10_ambient_aerosol_particles_in_air",
          "opensensor:property": "http://vocab.nerc.ac.uk/standard_name/mass_concentration_of_pm10_ambient_aerosol_particles_in_air/",
          "opensensor:procedure": "pmsa003",
          "opensensor:field_id": 18,
          "opensensor:valid_when": {
            "column": "pms_status",
            "equals": 4
          },
          "opensensor:settle_s": 180,
          "opensensor:aggregate": "mean",
          "opensensor:plausible_range": [
            0,
            1000
          ]
        },
        {
          "name": "pm25_cf1_ug_m3",
          "type": "int32",
          "unit": "ug/m3",
          "roles": [
            "physicalMeasurement"
          ],
          "opensensor:property": "urn:walkthru-earth:property:pm25_cf1_mass_concentration",
          "opensensor:procedure": "pmsa003",
          "opensensor:field_id": 14,
          "opensensor:valid_when": {
            "column": "pms_status",
            "equals": 4
          },
          "opensensor:settle_s": 180,
          "opensensor:aggregate": "mean",
          "opensensor:plausible_range": [
            0,
            1000
          ]
        },
        {
          "name": "particles_gt03_per_01l",
          "type": "int32",
          "unit": "{particles}/dL",
          "roles": [
            "physicalMeasurement"
          ],
          "opensensor:property": "urn:walkthru-earth:property:particle_count_gt_0p3um",
          "opensensor:procedure": "pmsa003",
          "opensensor:field_id": 19,
          "opensensor:valid_when": {
            "column": "pms_status",
            "equals": 4
          },
          "opensensor:settle_s": 180,
          "opensensor:aggregate": "mean",
          "opensensor:plausible_range": [
            0,
            65535
          ]
        },
        {
          "name": "imu_temperature_c",
          "type": "float",
          "unit": "Cel",
          "roles": [
            "auxiliaryInformation"
          ],
          "opensensor:procedure": "m5_imu",
          "opensensor:field_id": 40,
          "opensensor:valid_when": {
            "column": "imu_temperature_c",
            "finite": true
          },
          "opensensor:aggregate": "mean",
          "opensensor:plausible_range": [
            -20,
            85
          ]
        },
        {
          "name": "accel_z_g",
          "type": "float",
          "unit": "[g]",
          "roles": [
            "auxiliaryInformation"
          ],
          "opensensor:procedure": "m5_imu",
          "opensensor:field_id": 33,
          "opensensor:valid_when": {
            "column": "accel_z_g",
            "finite": true
          },
          "opensensor:aggregate": "mean"
        },
        {
          "name": "battery_percent",
          "type": "int32",
          "unit": "%",
          "roles": [
            "auxiliaryInformation"
          ],
          "opensensor:procedure": "m5_power",
          "opensensor:field_id": 48,
          "opensensor:aggregate": "point",
          "opensensor:plausible_range": [
            0,
            100
          ]
        }
      ]
    }
  },
  "links": [
    {
      "rel": "describedby",
      "href": "https://github.com/walkthru-earth/aq-parquet/blob/ca0397ff9aa7b955f9dd444a95b65a5db44dea9a/firmware/esp-idf-cores3/bringup/telemetry_fields.inc",
      "type": "text/plain",
      "title": "Firmware column dictionary",
      "file:checksum": "1220e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b"
    }
  ],
  "firmware": {
    "schema": "cores3-telemetry-v5",
    "proto_min": 3,
    "dict": "e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b",
    "live_keys": {
      "event_time_utc_us": "utc",
      "pms_status": "pms",
      "pm1_atmospheric_ug_m3": "pm1",
      "pm25_atmospheric_ug_m3": "pm25",
      "pm10_atmospheric_ug_m3": "pm10",
      "pm25_cf1_ug_m3": "c25",
      "particles_gt03_per_01l": "n03",
      "imu_temperature_c": "imu_t",
      "battery_percent": "pct"
    },
    "unit_labels": {
      "device_id": "1",
      "event_time_utc_us": "us_since_unix_epoch",
      "pms_status": "code",
      "particles_gt03_per_01l": "count/0.1L",
      "imu_temperature_c": "degC",
      "accel_z_g": "g"
    }
  }
}
"""

internal const val CORES3_DICT = "e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b"
internal const val OTHER_DICT = "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82"
internal const val DESC_A = "fb4230b3eb6e8eac881c719f900c8bd9c7e4bbd6ad247520a27368ec9d9a7218"
internal const val DESC_B = "1111111111111111111111111111111111111111111111111111111111111111"

/** A small board template around [columns], a JSON array body. */
internal fun template(
    columns: String,
    liveKeys: String = "{}",
    dict: String = OTHER_DICT,
    sampleIntervalS: String = "10",
): String = """
{
  "type": "OpenSensorTemplate",
  "template_version": "0.1.0",
  "id": "test-board-v1",
  "instruments": ["probe"],
  "opensensor:sample_interval_s": $sampleIntervalS,
  "item_assets": {"data": {"type": "application/vnd.apache.parquet", "roles": ["data"],
    "table:primary_datetime": "event_time_utc_us", "table:columns": [$columns]}},
  "links": [{"rel": "describedby", "href": "https://example.org/fields.inc", "type": "text/plain",
    "file:checksum": "1220$dict"}],
  "firmware": {"schema": "test-board-v1", "proto_min": 3, "dict": "$dict", "live_keys": $liveKeys}
}
"""

/** A thing Collection for the CoreS3 template, with titles, one scale and a runtime interval of 30 s. */
internal val CORES3_COLLECTION = """
{
  "type": "Collection",
  "stac_version": "1.1.0",
  "id": "thing-3f2b9c1e",
  "title": "Osu thing",
  "license": "CC-BY-4.0",
  "opensensor:sample_interval_s": 30,
  "opensensor:scales": {
    "who-2021-pm25": {
      "source": "https://www.who.int/publications/i/item/9789240034228",
      "unit": "ug/m3",
      "guideline": 5,
      "steps": [
        {"upper": 5, "label": {"en": "Guideline", "ar": "الحد الإرشادي"}, "color_hint": "2E7D32"},
        {"upper": 10, "label": "Interim target 4", "color_hint": "9E9D24"},
        {"upper": null, "label": {"en": "Above interim target 1"}, "color_hint": "C62828"}
      ]
    }
  },
  "item_assets": {"data": {"type": "application/vnd.apache.parquet", "roles": ["data"],
    "table:columns": [
      {"name": "pm25_atmospheric_ug_m3", "type": "int32", "title": "PM2.5", "unit": "ug/m3",
       "roles": ["physicalMeasurement"], "opensensor:scale": "who-2021-pm25"},
      {"name": "pms_status", "type": "int32", "title": "Particle sensor status", "roles": ["qualityInformation"]}
    ]}},
  "links": [
    {"rel": "describedby", "href": "https://example.org/fields.inc", "type": "text/plain",
     "file:checksum": "1220$CORES3_DICT"},
    {"rel": "describedby", "href": "https://example.org/templates/cores3-telemetry-v5.json",
     "type": "application/json", "file:checksum": "1220$DESC_A"},
    {"rel": "license", "href": "https://creativecommons.org/licenses/by/4.0/"}
  ]
}
"""

/** A bundled app profile for the CoreS3 dictionary. */
internal val CORES3_PROFILE = """
{
  "dict": "$CORES3_DICT",
  "family": "air",
  "primary": ["pm25_atmospheric_ug_m3", "no_such_column", "pm10_atmospheric_ug_m3"],
  "hero": "mood",
  "display_step": {"pm25_atmospheric_ug_m3": 1, "imu_temperature_c": 0.5},
  "labels": {"pm25_atmospheric_ug_m3": {"en": "PM2.5", "ar": "جسيمات 2.5"}},
  "unknown_field": true
}
"""
