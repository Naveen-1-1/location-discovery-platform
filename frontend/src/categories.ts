// Marker colours per app category (codes from data/categories.yaml / place.category).
export const CATEGORY_COLORS: Record<string, string> = {
  food: "#e4572e",
  grocery: "#2e933c",
  pharmacy: "#c0398b",
  gym: "#f28f3b",
  park: "#3a7d44",
  bank: "#2a6f97",
  atm: "#61a5c2",
  ev_charging: "#9b5de5",
  bike_share: "#00a6a6",
  transit: "#3d405b",
  library: "#8d6e63",
  hospital: "#d62828",
  community_centre: "#bc6c25",
  wifi: "#4361ee",
};

export const FALLBACK_COLOR = "#666666";

export function formatDistance(meters: number): string {
  return meters < 1000 ? `${meters} m` : `${(meters / 1000).toFixed(1)} km`;
}

/** Under a minute shows as "< 1 min", so a drive the engine rounds to 0 s never reads "0 min". */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return "< 1 min";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const rest = minutes % 60;
  return rest === 0 ? `${Math.floor(minutes / 60)} h` : `${Math.floor(minutes / 60)} h ${rest} min`;
}

export const MODE_LABELS = { walk: "Walk", bike: "Bike", drive: "Drive" } as const;

export const MODE_VERBS = { walk: "walking", bike: "cycling", drive: "driving" } as const;

/** Reachable-area limits in minutes, and their colours: one hue, darker for nearer (sequential). */
export const ISOCHRONE_MINUTES = [5, 10, 15] as const;
export const ISOCHRONE_COLORS: Record<number, string> = { 5: "#1c5cab", 10: "#3987e5", 15: "#86b6ef" };

export const ROUTING_CREDIT = 'Routing © <a href="https://openrouteservice.org">openrouteservice.org</a>';

/** Credits for display-only sources (ADR 0029), shown while their places are on screen. */
export const BIKE_SHARE_CREDIT = 'Bike share data © <a href="https://bluebikes.com/system-data">Bluebikes</a>';
export const TRANSIT_CREDIT = 'Data provided by <a href="https://www.mbta.com/developers">MassDOT / MBTA</a>';

/** Credits the sources ask for, shown while their data is on screen (data/sources.yaml "attribution"). */
export const SNAP_CREDIT =
  'SNAP retailers: <a href="https://www.fns.usda.gov/snap/retailer-locator">USDA Food and Nutrition Service</a>, ' +
  '<a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>, modified';
export const MASSGIS_CREDIT =
  'Hospitals: <a href="https://www.mass.gov/orgs/massgis-bureau-of-geographic-information">MassGIS</a>, Commonwealth of Massachusetts EOTSS';

export const FILTER_LABELS = {
  openNow: "Open now",
  snap: "Accepts SNAP/EBT",
  licensed: "Licensed",
  passedInspection: "Passed inspection",
} as const;

/** Where a place's hours come from; licensed hours are the latest it may stay open, not when it is open. */
export const HOURS_SOURCES = {
  osm: "Opening hours from OpenStreetMap",
  cambridge_permit: "Hours on its Cambridge food permit",
  boston_licence: "Licensed hours (Boston Licensing Board): the latest it may stay open",
} as const;
