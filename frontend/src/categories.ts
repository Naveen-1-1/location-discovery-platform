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
};

export const FALLBACK_COLOR = "#666666";

export function formatDistance(meters: number): string {
  return meters < 1000 ? `${meters} m` : `${(meters / 1000).toFixed(1)} km`;
}
