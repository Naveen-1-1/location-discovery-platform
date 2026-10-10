import { describe, expect, it } from "vitest";
import { nearbyUrl } from "./api";

describe("nearbyUrl", () => {
  it("rounds coordinates and sorts categories so equal queries share a cache key", () => {
    const a = nearbyUrl({ lat: 42.3550123, lon: -71.0656789, radius: 1500, categories: ["gym", "food"] });
    const b = nearbyUrl({ lat: 42.35501, lon: -71.06568, radius: 1500, categories: ["food", "gym"] });
    expect(a).toBe(b);
    expect(a).toBe("/api/places/nearby?lat=42.35501&lon=-71.06568&radius=1500&limit=100&category=food&category=gym");
  });

  it("omits the category filter when none is selected", () => {
    expect(nearbyUrl({ lat: 42.35, lon: -71.06, radius: 500, categories: [], limit: 20 })).not.toContain("category=");
  });
});
