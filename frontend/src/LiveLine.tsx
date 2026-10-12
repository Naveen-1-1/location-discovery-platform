// A place's live line ("4 bikes · 11 free docks", "Elevator out"), shared by the results list and the map popup.
import type { NearbyPlace, NearbyResponse } from "./api";
import { describeAlerts, describeDock, type DockStatus, type LiveItem, type LiveState, type StopAlerts } from "./live";

/** A place's live data: the stream's newest if it has the key, else what came with the search. */
export type PlaceLive = { topic: string; item: LiveItem; stale: boolean };

export function liveFor(p: NearbyPlace, live: LiveState, result: NearbyResponse | undefined): PlaceLive | null {
  if (!p.liveKey) return null;
  const topic = p.liveKey.slice(0, p.liveKey.indexOf(":"));
  // A stream that has its snapshot is the truth, even when it says "no alerts here" by having no item.
  const streamed = live.topics[topic] !== undefined;
  const item = streamed ? live.items.get(p.liveKey) : p.live;
  if (!item) return null;
  const health = live.topics[topic] ?? result?.live?.topics[topic];
  return { topic, item, stale: health?.stale ?? false };
}

/** The line for a place's live data, with an "out of date" badge while the feed is stale. */
export function LiveLine({ live }: { live: PlaceLive | null }) {
  if (!live) return null;
  const text = live.topic === "bikes" ? describeDock(live.item as DockStatus) : describeAlerts(live.item as StopAlerts);
  if (!text) return null;
  return (
    <span className={live.topic === "bikes" ? "live" : "live warn"}>
      {text}
      {live.stale && <span className="badge" title="The live feed has not updated for a few minutes">out of date</span>}
    </span>
  );
}
