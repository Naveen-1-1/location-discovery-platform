// Typed client for the api service. In development Vite proxies /api to localhost:8080.
import type { Feature, FeatureCollection, LineString, MultiPolygon, Polygon } from "geojson";
import type { DockStatus, StopAlerts, TopicHealth } from "./live";

export type Category = { code: string; label: string };

export type TravelMode = "walk" | "bike" | "drive";

/** What the filters know about a place. */
export type PlaceFacts = {
  acceptsSnap: boolean;
  /** Holds an active Boston or Cambridge food or premises licence. */
  licensed: boolean;
  /** The latest inspection; null if never inspected. */
  inspection: "passed" | "failed" | "other" | null;
  /** By the place's regular week, at the time of the answer; null when its hours are unknown. */
  openNow: boolean | null;
};

export type PlaceFilters = { openNow: boolean; snap: boolean; licensed: boolean; passedInspection: boolean };

export const NO_FILTERS: PlaceFilters = { openNow: false, snap: false, licensed: false, passedInspection: false };

export type NearbyPlace = {
  id: number;
  name: string;
  category: string;
  address: string | null;
  lat: number;
  lon: number;
  /** Straight-line distance from the search's snapped origin. */
  distanceMeters: number;
  /** Null for a straight-line search, a fallback, or a place the routing engine cannot reach. */
  travelSeconds: number | null;
  travelMeters: number | null;
  /** Set for places with live data, e.g. "bikes:<station_id>"; the same key as in the live stream. */
  liveKey: string | null;
  /** The live data at the time of the search; the live stream keeps it current after that. */
  live: DockStatus | StopAlerts | null;
  facts: PlaceFacts;
};

/** One place's panel (GET /api/places/{id}). */
export type PlaceDetails = {
  id: number;
  name: string;
  category: string;
  address: string | null;
  phone: string | null;
  website: string | null;
  lat: number;
  lon: number;
  liveKey: string | null;
  facts: {
    acceptsSnap: boolean;
    licensed: boolean;
    inspection: { result: "passed" | "failed" | "other"; at: string | null; failedChecks12m: number | null } | null;
    /** source: "osm" (published), "cambridge_permit", or "boston_licence" (the latest the licence allows). */
    hours: { text: string; source: "osm" | "cambridge_permit" | "boston_licence"; openNow: boolean } | null;
    parkFeatures: { name: string; count: number }[];
  };
  /** Ids from data/sources.yaml, for credits. */
  sources: string[];
};

/** How the search's live data went. */
export type LiveSummary = {
  /** "unavailable" when live-hub was slow or down: the places came without live counts. */
  status: "ok" | "unavailable";
  topics: Record<string, TopicHealth>;
};

export type NearbyResponse = {
  snappedCell: string;
  lat: number;
  lon: number;
  /** How far the search's real starting point can be from the snapped one the times are measured from. */
  maxSnapErrorMeters: number;
  mode: TravelMode | null;
  radiusMeters: number;
  /** The routing engine that gave the travel times ("openrouteservice", "valhalla"), or "straight_line". */
  travelTimeSource: string;
  /** Why a search with a mode has no travel times ("timeout", "breaker_open", …); null otherwise. */
  fallback: string | null;
  driveWithoutTraffic: boolean;
  places: NearbyPlace[];
  /** Null when no place in the answer has live data. */
  live: LiveSummary | null;
  /** With "open now" on, places nearby left out because their hours are unknown; null otherwise. */
  hiddenForUnknownHours: number | null;
};

export type NearbyQuery = {
  lat: number;
  lon: number;
  /** Null for a straight-line search within radius. */
  mode: TravelMode | null;
  radius: number;
  categories: readonly string[];
  filters?: PlaceFilters;
  limit?: number;
};

/** What the api says about a route or an isochrone besides its geometry. */
type RoutedProperties = {
  mode: TravelMode;
  travelTimeSource: string;
  driveWithoutTraffic: boolean;
  snappedCell: string;
  maxSnapErrorMeters: number;
};

export type RouteFeature = Feature<
  LineString,
  RoutedProperties & { placeId: number; placeName: string; seconds: number; meters: number }
>;

/** One area per limit, smallest first. */
export type IsochroneCollection = FeatureCollection<Polygon | MultiPolygon, { minutes: number }> & RoutedProperties;

type Origin = { lat: number; lon: number };

/** Coordinates go out rounded to 5 decimals (~1 m): enough precision, and stable cache keys. */
function originParams(o: Origin): URLSearchParams {
  return new URLSearchParams({ lat: o.lat.toFixed(5), lon: o.lon.toFixed(5) });
}

export function nearbyUrl(q: NearbyQuery): string {
  const params = originParams(q);
  // With a mode the server searches the mode's own radius and sorts by travel time.
  if (q.mode) params.set("mode", q.mode);
  else params.set("radius", String(q.radius));
  params.set("limit", String(q.limit ?? 100));
  for (const c of [...q.categories].sort()) params.append("category", c);
  const f = q.filters ?? NO_FILTERS;
  if (f.openNow) params.set("open", "now");
  if (f.snap) params.set("snap", "true");
  if (f.licensed) params.set("licensed", "true");
  if (f.passedInspection) params.set("inspection", "passed");
  return `/api/places/nearby?${params}`;
}

export function routeUrl(origin: Origin, placeId: number, mode: TravelMode): string {
  const params = originParams(origin);
  params.set("place", String(placeId));
  params.set("mode", mode);
  return `/api/route?${params}`;
}

export function isochroneUrl(origin: Origin, mode: TravelMode, minutes: readonly number[]): string {
  const params = originParams(origin);
  params.set("mode", mode);
  params.set("minutes", [...minutes].sort((a, b) => a - b).join(","));
  return `/api/isochrone?${params}`;
}

async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(url, { signal });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      detail = ((await res.json()) as { detail?: string }).detail ?? detail; // problem+json from the api
    } catch {
      // not JSON (e.g. the dev proxy saying the api is down)
    }
    throw new Error(`${res.status} ${detail}`);
  }
  return (await res.json()) as T;
}

export const fetchCategories = (signal?: AbortSignal) => getJson<Category[]>("/api/categories", signal);

export const fetchNearby = (q: NearbyQuery, signal?: AbortSignal) => getJson<NearbyResponse>(nearbyUrl(q), signal);

/** What the search box shows (GET /api/search). */
export type SearchResult = {
  places: { id: number; name: string; category: string; address: string | null; lat: number; lon: number; distanceMeters: number }[];
  addresses: { label: string; lat: number; lon: number; kind: string }[];
  /** "ok", "too_short" (under three letters), "busy", "timeout" or "unavailable". */
  addressStatus: string;
};

export function searchUrl(query: string, near: Origin): string {
  const params = new URLSearchParams({ q: query.trim(), lat: near.lat.toFixed(3), lon: near.lon.toFixed(3) });
  return `/api/search?${params}`;
}

export const fetchSearch = (query: string, near: Origin, signal?: AbortSignal) =>
  getJson<SearchResult>(searchUrl(query, near), signal);

export const fetchPlace = (id: number, signal?: AbortSignal) => getJson<PlaceDetails>(`/api/places/${id}`, signal);

export const fetchRoute = (origin: Origin, placeId: number, mode: TravelMode, signal?: AbortSignal) =>
  getJson<RouteFeature>(routeUrl(origin, placeId, mode), signal);

export const fetchIsochrones = (origin: Origin, mode: TravelMode, minutes: readonly number[], signal?: AbortSignal) =>
  getJson<IsochroneCollection>(isochroneUrl(origin, mode, minutes), signal);
