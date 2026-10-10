import { layers, namedFlavor } from "@protomaps/basemaps";
import type { StyleSpecification } from "maplibre-gl";

// Basemap from our own PMTiles file (tools/build-pmtiles.sh), read with HTTP range requests (ADR 0007).
// Fonts and sprites come from Protomaps' public asset site during development; they are self-hosted at deployment.
const ASSETS = "https://protomaps.github.io/basemaps-assets";

export function basemapStyle(): StyleSpecification {
  return {
    version: 8,
    glyphs: `${ASSETS}/fonts/{fontstack}/{range}.pbf`,
    sprite: `${ASSETS}/sprites/v4/light`,
    sources: {
      protomaps: {
        type: "vector",
        url: `pmtiles://${window.location.origin}/tiles/boston.pmtiles`,
        attribution:
          '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org/copyright">OpenStreetMap</a>',
      },
    },
    layers: layers("protomaps", namedFlavor("light"), { lang: "en" }),
  };
}
