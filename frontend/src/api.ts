// Typed client for the api service. In development Vite proxies /api to localhost:8080.

export type Category = { code: string; label: string };

export type NearbyPlace = {
  id: number;
  name: string;
  category: string;
  address: string | null;
  lat: number;
  lon: number;
  distanceMeters: number;
};

export type NearbyResponse = {
  lat: number;
  lon: number;
  radiusMeters: number;
  /** "straight_line" in Phase 1; routed sources ("ors", "valhalla") arrive in Phase 3. */
  travelTimeSource: string;
  places: NearbyPlace[];
};

export type NearbyQuery = {
  lat: number;
  lon: number;
  radius: number;
  categories: readonly string[];
  limit?: number;
};

/** Coordinates go out rounded to 5 decimals (~1 m): enough precision, and stable cache keys. */
export function nearbyUrl(q: NearbyQuery): string {
  const params = new URLSearchParams({
    lat: q.lat.toFixed(5),
    lon: q.lon.toFixed(5),
    radius: String(q.radius),
    limit: String(q.limit ?? 100),
  });
  for (const c of [...q.categories].sort()) params.append("category", c);
  return `/api/places/nearby?${params}`;
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
