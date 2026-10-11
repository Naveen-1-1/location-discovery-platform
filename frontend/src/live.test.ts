import { describe, expect, it } from "vitest";
import { applyItem, applySnapshot, applyStatus, describeAlerts, describeDock, EMPTY_LIVE, type DockStatus, type LiveState } from "./live";

const bikesAt = (s: LiveState, key: string) => (s.items.get(key) as DockStatus | undefined)?.bikes;

const dock = (id: string, bikes: number, extra: Partial<DockStatus> = {}): DockStatus => ({
  id, bikes, ebikes: 0, docks: 10, installed: true, renting: true, returning: true, ...extra,
});

describe("live stream state", () => {
  it("replaces a topic's items on each snapshot, as after a reconnect", () => {
    let s = applySnapshot(EMPTY_LIVE, { topic: "bikes", stale: false, updated: 1, items: [dock("a", 3), dock("b", 0)] });
    s = applySnapshot(s, { topic: "bikes", stale: true, updated: 2, items: [dock("a", 5)] });
    expect([...s.items.keys()]).toEqual(["bikes:a"]);
    expect(bikesAt(s, "bikes:a")).toBe(5);
    expect(s.topics.bikes).toEqual({ stale: true, updated: 2 });
  });

  it("applies changes and removals one key at a time without touching the old state", () => {
    const before = applySnapshot(EMPTY_LIVE, { topic: "bikes", stale: false, updated: 1, items: [dock("a", 3), dock("b", 1)] });
    let s = applyItem(before, "bikes", dock("a", 2));
    s = applyItem(s, "bikes", { id: "b", removed: true });
    expect(bikesAt(s, "bikes:a")).toBe(2);
    expect(s.items.has("bikes:b")).toBe(false);
    expect(bikesAt(before, "bikes:a")).toBe(3);
  });

  it("tracks a topic going stale and fresh again", () => {
    const s = applyStatus(EMPTY_LIVE, { topic: "bikes", stale: true, updated: 9 });
    expect(s.topics.bikes.stale).toBe(true);
    expect(applyStatus(s, { topic: "bikes", stale: false, updated: 10 }).topics.bikes.stale).toBe(false);
  });
});

describe("describeDock", () => {
  it("says what a rider can do there", () => {
    expect(describeDock(dock("a", 4, { ebikes: 1, docks: 11 }))).toBe("4 bikes (1 e-bike) · 11 free docks");
    expect(describeDock(dock("a", 1, { docks: 1 }))).toBe("1 bike · 1 free dock");
    expect(describeDock(dock("a", 2, { returning: false }))).toBe("2 bikes · no returns");
    expect(describeDock(dock("a", 0, { renting: false, docks: 5 }))).toBe("Returns only · 5 free docks");
    expect(describeDock(dock("a", 0, { installed: false }))).toBe("Closed");
  });
});

describe("describeAlerts", () => {
  const alert = (detail: string | null) => ({ id: detail ?? "x", effect: null, detail, severity: null, header: null });
  it("puts outages first and counts the rest", () => {
    expect(describeAlerts({ id: "s", elevator: true, escalator: false, alerts: [alert("ELEVATOR_CLOSURE"), alert(null), alert("DETOUR")] }))
      .toBe("Elevator out · 2 alerts");
    expect(describeAlerts({ id: "s", elevator: false, escalator: true, alerts: [alert("ESCALATOR_CLOSURE")] })).toBe("Escalator out");
    expect(describeAlerts({ id: "s", elevator: false, escalator: false, alerts: [alert(null)] })).toBe("1 alert");
  });
});

describe("minutesUntil", () => {
  it("rounds to minutes and never shows a past time", async () => {
    const { minutesUntil } = await import("./Departures");
    expect(minutesUntil(1_000, 1_000_000)).toBe("now");
    expect(minutesUntil(1_200, 1_000_000)).toBe("3 min");
    expect(minutesUntil(990, 1_000_000)).toBe("now");
  });
});
