# stac-opensensor fixtures

Copied byte for byte from [walkthru-earth/stac-opensensor](https://github.com/walkthru-earth/stac-opensensor)
at tag v0.1.0, commit `83787768cc8451109d48a03d4ef5493ffcd403dd`. Renew every file from the same commit
when the pin moves.

| File here | Path in stac-opensensor |
| --- | --- |
| `item-fixed.json` | `examples/thing=3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13/year=2026/month=10/day=24/data_1300_0f3a9c2e5b7d41e8a6c3f0b2d9e4a713_2881-2970-0.json` |
| `item-moving.json` | `examples/thing=7c1e4b9a-3d52-4f86-a0b7-e9d2c5f81a46/year=2026/month=10/day=24/data_0815_5e9d0c7b31a84f62b8d47a10c3e6f295_412-487-0.json` |
| `collection-fixed.json` | `examples/thing=3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13/collection.json` |
| `collection-moving.json` | `examples/thing=7c1e4b9a-3d52-4f86-a0b7-e9d2c5f81a46/collection.json` |
| `rows-fixed.json` | `fixtures/thing=3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13/data_1300_0f3a9c2e5b7d41e8a6c3f0b2d9e4a713_2881-2970-0.rows.json` |
| `rows-moving.json` | `fixtures/thing=7c1e4b9a-3d52-4f86-a0b7-e9d2c5f81a46/data_0815_5e9d0c7b31a84f62b8d47a10c3e6f295_412-487-0.rows.json` |
| `owner-fixed.json` | `fixtures/thing=3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13/owner.json` |
| `owner-moving.json` | `fixtures/thing=7c1e4b9a-3d52-4f86-a0b7-e9d2c5f81a46/owner.json` |
| `footers/` | `footers/m5stack-cores3.json` and `footers/waveshare-esp32-s3-sim7670g.json` |
| `templates/` | `templates/`, the two board templates the footers name. Git keeps their bytes (`-text`) |
| `footer.json`, `profile.json`, `schema.json` | `json-schema/` |

`ProfileExampleTest` rebuilds the Items from the rows, `ThingCollectionsTest` rebuilds the Collections
from the owner config, the template, the footer and the Items, and both compare them with the examples
as JSON values.
