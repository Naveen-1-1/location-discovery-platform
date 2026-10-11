import type { FeatureCollection } from "geojson";
import { addProtocol, setWorkerUrl } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { Protocol } from "pmtiles";
import { useMemo } from "react";
import Map, {
  Layer,
  Marker,
  NavigationControl,
  Popup,
  Source,
  type MapLayerMouseEvent,
} from "react-map-gl/maplibre";
import type { NearbyPlace } from "./api";
import { CATEGORY_COLORS, FALLBACK_COLOR, formatDistance } from "./categories";
import { basemapStyle } from "./mapStyle";
// MapLibre finds its worker relative to its own module URL, which breaks once a bundler moves that module (Vite's dep
// cache in development, the app chunk in production). Let Vite bundle the worker and tell MapLibre where it is.
import maplibreWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";

setWorkerUrl(maplibreWorkerUrl);
addProtocol("pmtiles", new Protocol().tile);

const PLACES_LAYER = "places";

const circleColor = [
  "match",
  ["get", "category"],
  ...Object.entries(CATEGORY_COLORS).flat(),
  FALLBACK_COLOR,
] as unknown as string;

type Props = {
  origin: { lat: number; lon: number };
  radius: number;
  places: NearbyPlace[];
  selected: NearbyPlace | null;
  onPickOrigin: (lat: number, lon: number) => void;
  onSelect: (place: NearbyPlace | null) => void;
};

export function MapView({ origin, radius, places, selected, onPickOrigin, onSelect }: Props) {
  const style = useMemo(basemapStyle, []);

  const geojson = useMemo<FeatureCollection>(
    () => ({
      type: "FeatureCollection",
      features: places.map((p) => ({
        type: "Feature",
        id: p.id,
        geometry: { type: "Point", coordinates: [p.lon, p.lat] },
        properties: { id: p.id, category: p.category },
      })),
    }),
    [places],
  );

  function handleClick(e: MapLayerMouseEvent) {
    const hit = e.features?.[0];
    if (hit) {
      onSelect(places.find((p) => p.id === hit.properties?.id) ?? null);
    } else {
      onPickOrigin(e.lngLat.lat, e.lngLat.lng);
    }
  }

  return (
    <Map
      initialViewState={{ latitude: origin.lat, longitude: origin.lon, zoom: radius > 2000 ? 13 : 14.5 }}
      mapStyle={style}
      interactiveLayerIds={[PLACES_LAYER]}
      onClick={handleClick}
      cursor="crosshair"
      style={{ width: "100%", height: "100%" }}
    >
      <NavigationControl position="top-right" />
      <Source
        id="places"
        type="geojson"
        data={geojson}
        attribution='Places © <a href="https://overturemaps.org">Overture Maps Foundation</a>, © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>'
      >
        <Layer
          id={PLACES_LAYER}
          type="circle"
          paint={{
            "circle-radius": 6,
            "circle-color": circleColor,
            "circle-stroke-width": 1.5,
            "circle-stroke-color": "#ffffff",
          }}
        />
      </Source>
      <Marker latitude={origin.lat} longitude={origin.lon} color="#111827" />
      {selected && (
        <Popup
          latitude={selected.lat}
          longitude={selected.lon}
          offset={10}
          closeOnClick={false}
          onClose={() => onSelect(null)}
        >
          <strong>{selected.name}</strong>
          {selected.address && <div>{selected.address}</div>}
          <div className="muted">{formatDistance(selected.distanceMeters)} away (straight line)</div>
        </Popup>
      )}
    </Map>
  );
}
