# Open source prior art

Part of [Thing plugins](README.md). Surveyed 2026-10-27 with `gh api` and Codeberg. License decides what we may copy. GPL and AGPL projects are studied for patterns only, never code.

## Top five

| # | Project | License | Copy | Avoid |
| --- | --- | --- | --- | --- |
| 1 | [Home Assistant Companion Android](https://github.com/home-assistant/android), [iOS](https://github.com/home-assistant/iOS) | Apache-2.0 | Phone sensors as value objects with per sensor permissions, `hasSensor()` probe and typed settings, off by default. `@ProvidesSensor` plus KSP generates the registry and a lint rule fails the build if one is missing. iOS `SensorProvider` is one protocol | `Int` string resources and `Context` in the interface |
| 2 | [Meshtastic Android](https://github.com/meshtastic/Meshtastic-Android) | GPL-3.0 | KMP with Compose Multiplatform, `core/*` and `feature/*`. Map in `commonMain` on maplibre-compose 0.20.0 with two `expect`/`actual` pairs. GeoJSON rebuilt only when keys change. `ConfigRoute` enum ties settings to wire config sections | a hand written screen per section |
| 3 | [Home Assistant core](https://github.com/home-assistant/core), see [AirGradient](https://github.com/home-assistant/core/blob/dev/homeassistant/components/airgradient/manifest.json) | Apache-2.0 | Static manifest per integration with discovery keys (`zeroconf`), so discovery routes a found device without loading code. Config flow steps return form, abort or entry. A closed set of selectors | Python dynamism |
| 4 | [phyphox](https://github.com/phyphox/phyphox-android) | GPL-3.0 | Declarative input descriptors (service UUID, characteristic, conversion, unit) make a simple BLE device usable without an app release | formulas in the descriptor |
| 5 | [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (moved to Codeberg, GitHub mirror archived) | AGPL-3.0 | Split between a static coordinator (what a device is, scan filters, bonding style, settings) and a live support class | 50 plus `supportsX()` booleans. Use a capability set of metric keys instead |

## Others

- [ESPHome](https://github.com/esphome/esphome). Each component lists the metrics it may emit with unit and class from a shared schema.
- [ThingsBoard device profiles](https://github.com/thingsboard/thingsboard). A kind is a profile, a device overrides it.
- [FROST-Server](https://github.com/FraunhoferIOSB/FROST-Server). SensorThings reference. Keep Datastream metadata apart from Observations.
- [openHAB add-ons](https://github.com/openhab/openhab-addons). Thing type XML with channels and config descriptions per binding.
- [openScale](https://github.com/oliexdev/openScale). Many BLE handlers behind one interface, a smaller Gadgetbridge.
- [OwnTracks](https://github.com/owntracks/android). Monitoring modes trade precision against battery. Ours fix precision at an H3 resolution, never raw fixes.
- [NoiseCapture](https://github.com/Universite-Gustave-Eiffel/NoiseCapture). Per phone microphone calibration as part of setup.
- [openSenseMap API](https://github.com/sensebox/openSenseMap-API). Board models as predefined sensor layouts that preset a box's Datastreams. The app itself is inactive since 2022.
- [react-jsonschema-form](https://github.com/rjsf-team/react-jsonschema-form), [JSON Forms](https://github.com/eclipsesource/jsonforms). Data schema split from UI schema. No mature Compose or KMP renderer was found, so we build a small one.

## KMP libraries

- [maplibre-compose](https://github.com/maplibre/maplibre-compose) (moved from `sargunv`), BSD-3, already used at 0.20.0. Pre-1.0, pin carefully.
- [Kable](https://github.com/JuulLabs/kable), Apache-2.0. Reference for a coroutine BLE API shape only, we keep our own links.
- [Metro](https://github.com/ZacSweers/metro), kotlin-inject with anvil, Koin. All offer multibindings on every KMP target. Since `:core` avoids DI frameworks, a hand written list checked by a test (or a KSP-generated one later) is the lightest option.
