import { describe, expect, it } from "vitest";
import { isochroneUrl, nearbyUrl, routeUrl } from "./api";
import { formatDuration } from "./categories";

describe("nearbyUrl", () => {
  it("rounds coordinates and sorts categories so equal queries share a cache key", () => {
    const a = nearbyUrl({ lat: 42.3550123, lon: -71.0656789, mode: null, radius: 1500, categories: ["gym", "food"] });
    const b = nearbyUrl({ lat: 42.35501, lon: -71.06568, mode: null, radius: 1500, categories: ["food", "gym"] });
    expect(a).toBe(b);
    expect(a).toBe("/api/places/nearby?lat=42.35501&lon=-71.06568&radius=1500&limit=100&category=food&category=gym");
  });

  it("omits the category filter when none is selected", () => {
    expect(nearbyUrl({ lat: 42.35, lon: -71.06, mode: null, radius: 500, categories: [], limit: 20 })).not.toContain(
      "category=",
    );
  });

  it("sends the mode instead of a radius for a travel-time search", () => {
    const url = nearbyUrl({ lat: 42.35, lon: -71.06, mode: "bike", radius: 1500, categories: [] });
    expect(url).toBe("/api/places/nearby?lat=42.35000&lon=-71.06000&mode=bike&limit=100");
  });
});

describe("routeUrl and isochroneUrl", () => {
  it("round the origin and sort the limits", () => {
    expect(routeUrl({ lat: 42.3550123, lon: -71.0656789 }, 7, "walk")).toBe(
      "/api/route?lat=42.35501&lon=-71.06568&place=7&mode=walk",
    );
    expect(isochroneUrl({ lat: 42.355, lon: -71.0656 }, "drive", [15, 5, 10])).toBe(
      "/api/isochrone?lat=42.35500&lon=-71.06560&mode=drive&minutes=5%2C10%2C15",
    );
  });
});

describe("formatDuration", () => {
  it("never shows zero minutes and switches to hours past an hour", () => {
    expect(formatDuration(0)).toBe("< 1 min");
    expect(formatDuration(59)).toBe("< 1 min");
    expect(formatDuration(90)).toBe("2 min");
    expect(formatDuration(3_600)).toBe("1 h");
    expect(formatDuration(3_900)).toBe("1 h 5 min");
  });
});
