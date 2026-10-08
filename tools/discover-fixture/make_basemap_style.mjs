// Writes the Discover basemap styles (light and dark) from @protomaps/basemaps 5.7.2.
//
//   mkdir -p /tmp/pm && cd /tmp/pm && npm install @protomaps/basemaps@5.7.2
//   node <repo>/tools/discover-fixture/make_basemap_style.mjs /tmp/pm/node_modules/@protomaps/basemaps
//
// The tiles are the Protomaps OpenStreetMap PMTiles build on source.coop, read with range requests.
// Glyphs and sprites come from the Protomaps basemaps-assets site. Labels are English. Map data
// © OpenStreetMap contributors (ODbL), shown on the map as "© OpenStreetMap".
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const pkg = process.argv[2];
if (!pkg) throw new Error("pass the path of node_modules/@protomaps/basemaps");
const { layers, namedFlavor } = await import(pathToFileURL(join(resolve(pkg), "dist/esm/index.js")).href);

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const out = join(repo, "feature/discover/src/commonMain/composeResources/files/basemap");
mkdirSync(out, { recursive: true });

const TILES = "pmtiles://https://data.source.coop/protomaps/openstreetmap/v4.pmtiles";
const ASSETS = "https://protomaps.github.io/basemaps-assets";

for (const [name, flavor] of [["light", "grayscale"], ["dark", "black"]]) {
  const style = {
    version: 8,
    name: `opensensor discover ${name}`,
    glyphs: `${ASSETS}/fonts/{fontstack}/{range}.pbf`,
    sprite: `${ASSETS}/sprites/v4/${flavor}`,
    sources: {
      protomaps: {
        type: "vector",
        url: TILES,
        attribution: "© OpenStreetMap",
      },
    },
    layers: layers("protomaps", namedFlavor(flavor), { lang: "en" }),
  };
  writeFileSync(join(out, `${name}.json`), JSON.stringify(style) + "\n");
  console.log(`${name}.json  layers=${style.layers.length}`);
}
