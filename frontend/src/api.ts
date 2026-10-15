// Typed client for the api service. In development Vite proxies /api to localhost:8080. The response types are
// generated from the api's own description (api-schema.ts, by npm run types from openapi.json); only what the api
// passes through as plain JSON gets a narrower type here: live-hub's items and GeoJSON geometry.
import type { Feature, FeatureCollection, LineString, MultiPolygon, Polygon } from "geojson";
import type { components } from "./api-schema";
import type { DockStatus, StopAlerts, TopicHealth } from "./live";

type Schemas = components["schemas"];

export type Category = Schemas["Category"];

export type TravelMode = NonNullable<Schemas["NearbyResponse"]["mode"]>;

/** What the filters know about a place. */
export type PlaceFacts = Schemas["PlaceFacts"];

export type PlaceFilters = { openNow: boolean; snap: boolean; licensed: boolean; passedInspection: boolean };

export const NO_FILTERS: PlaceFilters = { openNow: false, snap: false, licensed: false, passedInspection: false };

/** A place found nearby; `live` is live-hub's data at the time of the search, kept current by the stream. */
export type NearbyPlace = Omit<Schemas["NearbyPlace"], "live"> & { live: DockStatus | StopAlerts | null };

/** One place's panel (GET /api/places/{id}). */
export type PlaceDetails = Schemas["PlaceDetails"];

/** How the search's live data went. */
export type LiveSummary = Omit<Schemas["LiveSummary"], "topics"> & { topics: Record<string, TopicHealth> };

export type NearbyResponse = Omit<Schemas["NearbyResponse"], "places" | "live"> & {
  places: NearbyPlace[];
  live: LiveSummary | null;
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

export type RouteFeature = Feature<LineString, Schemas["RouteProperties"]>;

/** One area per limit, smallest first; the other members describe the request. */
export type IsochroneCollection = FeatureCollection<Polygon | MultiPolygon, Schemas["IsochroneProperties"]> &
  Omit<Schemas["IsochroneCollection"], "type" | "features">;

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

/** GETs JSON; a failed answer throws with the status and, from problem+json, its detail. */
export async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
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
export type SearchResult = Schemas["SearchResult"];

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
