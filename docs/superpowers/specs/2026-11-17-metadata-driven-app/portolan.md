# Portolan, later target

Part of [Metadata-driven app](README.md). 2026-11-17. Not planned work. A note so the contract changes do not close the door.

Portolan reads a STAC catalog of cloud-native files and expects some things the opensensor catalog does not publish yet.

| Portolan wants | Today | Path |
| --- | --- | --- |
| `table:columns` with a `description` per column | Name, type, unit, roles, property URI. No description | Column `description` from owner decision 1 fills it |
| A README and an AGENTS file per Collection | None | Generated per Thing Collection, a later `core/stac` change |
| `providers` on each Collection | The owner site (pages 5 and 6) fills Thing Collection `providers` (`ThingCollections.kt`) | Already there, check the role names |
| Presentation as a first-class `style` asset | Scales and labels live in the registry and the app | A `style` asset per Collection, built from the registry |

Rules that keep it open.

- Display hints stay in published metadata (template columns and the registry), never only in app code.
- The registry is a plain JSON file with a stable URL, so a `style` asset can be generated from it.
- Setup stays out of STAC. A Portolan reader never sees setup steps or config keys.

No work package here. Revisit after M10 and S8 ship.
