// A place's live line ("4 bikes · 11 free docks"), shared by the results list and the map popup.
import type { NearbyPlace, NearbyResponse } from "./api";
import { describeDock, type DockStatus, type LiveState } from "./live";

/** A place's live data: the stream's newest if it has the key, else what came with the search. */
export type PlaceLive = { item: DockStatus; stale: boolean };

export function liveFor(p: NearbyPlace, live: LiveState, result: NearbyResponse | undefined): PlaceLive | null {
  if (!p.liveKey) return null;
  const item = live.items.get(p.liveKey) ?? p.live;
  if (!item) return null;
  const topic = p.liveKey.slice(0, p.liveKey.indexOf(":"));
  const health = live.topics[topic] ?? result?.live?.topics[topic];
  return { item, stale: health?.stale ?? false };
}

/** "4 bikes · 11 free docks", with an "out of date" badge while the feed is stale (ADR 0024). */
export function LiveLine({ live }: { live: PlaceLive | null }) {
  if (!live) return null;
  return (
    <span className="live">
      {describeDock(live.item)}
      {live.stale && <span className="badge" title="The live feed has not updated for a few minutes">out of date</span>}
    </span>
  );
}
