// Writes the Discover basemap styles (light and dark) from @protomaps/basemaps 5.7.2.
//
//   mkdir -p /tmp/pm && cd /tmp/pm && npm install @protomaps/basemaps@5.7.2
//   node <repo>/tools/discover-fixture/make_basemap_style.mjs /tmp/pm/node_modules/@protomaps/basemaps
//
// The tiles are the Protomaps OpenStreetMap PMTiles build on source.coop, read with range requests.
// Glyphs and sprites come from the Protomaps basemaps-assets site. Labels are English. Map data
// © OpenStreetMap contributors (ODbL), shown on the map as "© OpenStreetMap".
//
// Each flavour also gets a "-latin" variant for renderers without bidi and Arabic shaping. The iOS
// maplibre-native-ffi build compiles the Qt stub `platform/qt/src/mln/bidi.cpp`, which neither
// reorders right-to-left text nor joins Arabic letters, so a name in Arabic, Hebrew or another
// complex script draws broken. The variant labels with `name:en`, else a Latin `name`, else nothing.
// Drop it once the Apple FFI build links ICU bidi like Android does.
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

// Protomaps tiles set `script` only when `name` is not Latin.
const LATIN_NAME = ["coalesce", ["get", "name:en"], ["case", ["has", "script"], "", ["get", "name"]]];

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

  const latin = { ...style, name: `${style.name} latin`, layers: style.layers.map(latinLabels) };
  writeFileSync(join(out, `${name}-latin.json`), JSON.stringify(latin) + "\n");
  console.log(`${name}-latin.json  labels=${latin.layers.filter((l) => l.layout?.["text-field"] === LATIN_NAME).length}`);
}

function latinLabels(layer) {
  const field = layer.layout?.["text-field"];
  if (field === undefined || !JSON.stringify(field).includes('"name"')) return layer;
  return { ...layer, layout: { ...layer.layout, "text-field": LATIN_NAME } };
}
