import type { FeatureCollection } from "geojson";
import { addProtocol, setWorkerUrl } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { Protocol } from "pmtiles";
import { useEffect, useMemo, useRef } from "react";
import Map, {
  Layer,
  Marker,
  NavigationControl,
  Popup,
  Source,
  type MapLayerMouseEvent,
  type MapRef,
} from "react-map-gl/maplibre";
import type { IsochroneCollection, NearbyPlace, PlaceDetails, RouteFeature, TravelMode } from "./api";
import { Departures } from "./Departures";
import type { StopAlerts, Vehicle } from "./live";
import { LiveLine, type PlaceLive } from "./LiveLine";
import { PlaceDetailsView } from "./PlaceFactsView";
import {
  CATEGORY_COLORS,
  FALLBACK_COLOR,
  formatDistance,
  formatDuration,
  ISOCHRONE_COLORS,
  MODE_VERBS,
  ROUTING_CREDITS,
} from "./categories";
import { basemapStyle } from "./mapStyle";
// MapLibre finds its worker relative to its own module URL, which breaks once a bundler moves that module (Vite's dep
// cache in development, the app chunk in production). Let Vite bundle the worker and tell MapLibre where it is.
import maplibreWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";

setWorkerUrl(maplibreWorkerUrl);
addProtocol("pmtiles", new Protocol().tile);

const PLACES_LAYER = "places";
const EMPTY: FeatureCollection = { type: "FeatureCollection", features: [] };

const circleColor = [
  "match",
  ["get", "category"],
  ...Object.entries(CATEGORY_COLORS).flat(),
  FALLBACK_COLOR,
] as unknown as string;

const isochroneColor = [
  "match",
  ["get", "minutes"],
  ...Object.entries(ISOCHRONE_COLORS).flatMap(([minutes, color]) => [Number(minutes), color]),
  "#86b6ef",
] as unknown as string;

type Props = {
  origin: { lat: number; lon: number };
  radius: number;
  places: NearbyPlace[];
  selected: NearbyPlace | null;
  /** The mode being asked for, which the route button uses. */
  mode: TravelMode | null;
  /** The mode the places on screen were sorted by; it labels their travel times. */
  resultMode: TravelMode | null;
  route: RouteFeature | undefined;
  /** "loading", an error message, or null. */
  routeState: string | null;
  isochrones: IsochroneCollection | undefined;
  /** The routing engine anything on screen came from, which then needs its credit; null for none. */
  routingEngine: string | null;
  /** Credits for display-only sources whose places are on screen; each id names its map source. */
  credits: { id: string; html: string }[];
  /** The selected place's live data, if it has any. */
  selectedLive: PlaceLive | null;
  /** The selected place's details, once they arrive. */
  details: PlaceDetails | undefined;
  /** MBTA vehicles to draw; empty unless asked for. */
  vehicles: Vehicle[];
  onPickOrigin: (lat: number, lon: number) => void;
  onSelect: (place: NearbyPlace | null) => void;
  onShowRoute: (place: NearbyPlace) => void;
};

export function MapView(props: Props) {
  const { origin, radius, places, selected, mode, resultMode, route, routeState, isochrones, routingEngine } = props;
  const routingCredit = routingEngine === null ? undefined : ROUTING_CREDITS[routingEngine];
  const style = useMemo(basemapStyle, []);
  const map = useRef<MapRef>(null);

  // Bring a new route fully into view, with room for the popup above it.
  useEffect(() => {
    const line = route?.geometry.coordinates;
    if (!line || line.length < 2) return;
    const lons = line.map((c) => c[0]);
    const lats = line.map((c) => c[1]);
    map.current?.fitBounds(
      [
        [Math.min(...lons), Math.min(...lats)],
        [Math.max(...lons), Math.max(...lats)],
      ],
      { padding: { top: 160, bottom: 60, left: 60, right: 60 }, maxZoom: 17, duration: 600 },
    );
  }, [route]);

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

  const vehicleDots = useMemo<FeatureCollection>(
    () => ({
      type: "FeatureCollection",
      features: props.vehicles.map((v) => ({
        type: "Feature",
        geometry: { type: "Point", coordinates: [v.lon, v.lat] },
        properties: { color: routeColor(v.route) },
      })),
    }),
    [props.vehicles],
  );

  // Largest area first, so each smaller one is drawn on top of it.
  const areas = useMemo<FeatureCollection>(
    () => (isochrones ? { ...isochrones, features: [...isochrones.features].reverse() } : EMPTY),
    [isochrones],
  );

  function handleClick(e: MapLayerMouseEvent) {
    const hit = e.features?.[0];
    if (hit) {
      props.onSelect(places.find((p) => p.id === hit.properties?.id) ?? null);
    } else {
      props.onPickOrigin(e.lngLat.lat, e.lngLat.lng);
    }
  }

  return (
    <Map
      ref={map}
      initialViewState={{ latitude: origin.lat, longitude: origin.lon, zoom: radius > 2000 ? 13 : 14.5 }}
      mapStyle={style}
      interactiveLayerIds={[PLACES_LAYER]}
      onClick={handleClick}
      cursor="crosshair"
      style={{ width: "100%", height: "100%" }}
    >
      <NavigationControl position="top-right" />
      <Source id="isochrones" type="geojson" data={areas}>
        <Layer id="isochrone-fill" type="fill" paint={{ "fill-color": isochroneColor, "fill-opacity": 0.22 }} />
        <Layer id="isochrone-line" type="line" paint={{ "line-color": isochroneColor, "line-width": 1.5 }} />
      </Source>
      <Source id="route" type="geojson" data={route ?? EMPTY}>
        <Layer
          id="route-line"
          type="line"
          layout={{ "line-cap": "round", "line-join": "round" }}
          paint={{ "line-color": "#2563eb", "line-width": 5, "line-opacity": 0.85 }}
        />
      </Source>
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
      <Source id="vehicles" type="geojson" data={vehicleDots}>
        <Layer
          id="vehicles"
          type="circle"
          paint={{ "circle-radius": 4, "circle-color": ["get", "color"], "circle-stroke-width": 1, "circle-stroke-color": "#111827" }}
        />
      </Source>
      {/* Puts the routing engine's credit in the attribution while anything on screen comes from it. MapLibre only
          credits sources a visible layer uses, hence the empty, transparent layer; the key swaps the source when the
          engine changes, since a source keeps the attribution it was added with. */}
      {routingCredit && (
        <Source key={routingCredit} id="routing-credit" type="geojson" data={EMPTY} attribution={routingCredit}>
          <Layer id="routing-credit" type="circle" paint={{ "circle-opacity": 0, "circle-radius": 0 }} />
        </Source>
      )}
      {props.credits.map((credit) => (
        <Source key={credit.id} id={credit.id} type="geojson" data={EMPTY} attribution={credit.html}>
          <Layer id={credit.id} type="circle" paint={{ "circle-opacity": 0, "circle-radius": 0 }} />
        </Source>
      ))}
      <Marker latitude={origin.lat} longitude={origin.lon} color="#111827" />
      {selected && (
        <Popup
          latitude={selected.lat}
          longitude={selected.lon}
          offset={10}
          closeOnClick={false}
          onClose={() => props.onSelect(null)}
        >
          <strong>{selected.name}</strong>
          {selected.address && <div>{selected.address}</div>}
          {props.selectedLive && props.selectedLive.topic === "bikes" && (
            <div>
              <LiveLine live={props.selectedLive} />
            </div>
          )}
          {selected.category === "transit" && selected.liveKey && (
            <Departures
              stop={selected.liveKey.slice(selected.liveKey.indexOf(":") + 1)}
              alerts={props.selectedLive?.topic === "alerts" ? (props.selectedLive.item as StopAlerts) : null}
            />
          )}
          {props.details && <PlaceDetailsView details={props.details} />}
          <div className="muted">
            {selected.travelSeconds !== null && resultMode
              ? `${formatDuration(selected.travelSeconds)} ${MODE_VERBS[resultMode]}, `
              : ""}
            {formatDistance(selected.distanceMeters)} away in a straight line
          </div>
          {mode && !route && (
            <button className="link" onClick={() => props.onShowRoute(selected)} disabled={routeState === "loading"}>
              {routeState === "loading" ? "Finding a route…" : "Show route"}
            </button>
          )}
          {route && (
            <div className="muted">
              Route: {formatDuration(route.properties.seconds)}, {formatDistance(route.properties.meters)}
              {route.properties.driveWithoutTraffic ? " (without traffic)" : ""}
            </div>
          )}
          {routeState && routeState !== "loading" && <div className="error">{routeState}</div>}
        </Popup>
      )}
    </Map>
  );
}

/** MBTA line colours; buses yellow, commuter rail purple, ferries teal. */
function routeColor(route: string | null): string {
  if (!route) return "#9ca3af";
  if (route === "Red" || route === "Mattapan") return "#da291c";
  if (route === "Orange") return "#ed8b00";
  if (route === "Blue") return "#003da5";
  if (route.startsWith("Green")) return "#00843d";
  if (route.startsWith("CR-")) return "#80276c";
  if (route.startsWith("Boat-")) return "#008eaa";
  return "#ffc72c";
}
