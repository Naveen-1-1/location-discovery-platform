// Live data from live-hub over one Server-Sent Events stream per browser tab (ADR 0018). In development Vite proxies
// /live to localhost:8081. The stream starts with a snapshot of every topic, then sends only what changed; after a
// reconnect (the browser's own, a few seconds later) the server sends a fresh snapshot, so nothing needs replaying.
import { useSyncExternalStore } from "react";

/** One Bluebikes dock's counts, as live-hub sends them. */
export type DockStatus = {
  id: string;
  bikes: number;
  ebikes: number;
  docks: number;
  installed: boolean;
  renting: boolean;
  returning: boolean;
};

export type TopicHealth = { stale: boolean; updated: number | null };

export type LiveState = {
  /** Whether the stream is open right now; false while the browser reconnects. */
  connected: boolean;
  /** Per topic ("bikes"), whether its feed is out of date. Missing until the first snapshot. */
  topics: Readonly<Record<string, TopicHealth>>;
  /** Live key ("bikes:<station_id>", the api's liveKey) → the item. */
  items: ReadonlyMap<string, DockStatus>;
};

export const EMPTY_LIVE: LiveState = { connected: false, topics: {}, items: new Map() };

type SnapshotEvent = { topic: string; stale: boolean; updated: number | null; items: DockStatus[] };
type StatusEvent = { topic: string; stale: boolean; updated: number | null };
type ItemEvent = (DockStatus & { removed?: false }) | { id: string; removed: true };

// ---- Pure updates (tested in live.test.ts) ----

export function applySnapshot(state: LiveState, e: SnapshotEvent): LiveState {
  const items = new Map([...state.items].filter(([key]) => !key.startsWith(`${e.topic}:`)));
  for (const item of e.items) items.set(`${e.topic}:${item.id}`, item);
  return { ...state, topics: { ...state.topics, [e.topic]: { stale: e.stale, updated: e.updated } }, items };
}

export function applyStatus(state: LiveState, e: StatusEvent): LiveState {
  return { ...state, topics: { ...state.topics, [e.topic]: { stale: e.stale, updated: e.updated } } };
}

export function applyItem(state: LiveState, topic: string, e: ItemEvent): LiveState {
  const items = new Map(state.items);
  const key = `${topic}:${e.id}`;
  if (e.removed) items.delete(key);
  else items.set(key, e);
  return { ...state, items };
}

/** "4 bikes (1 e-bike) · 11 free docks", or why the dock cannot be used. */
export function describeDock(d: DockStatus): string {
  if (!d.installed || !d.renting) return d.returning && d.installed ? `Returns only · ${docksText(d.docks)}` : "Closed";
  const bikes = `${d.bikes} ${d.bikes === 1 ? "bike" : "bikes"}${d.ebikes > 0 ? ` (${d.ebikes} e-bike${d.ebikes === 1 ? "" : "s"})` : ""}`;
  return d.returning ? `${bikes} · ${docksText(d.docks)}` : `${bikes} · no returns`;
}

function docksText(docks: number): string {
  return `${docks} free ${docks === 1 ? "dock" : "docks"}`;
}

// ---- The one stream per tab ----

const TOPICS = ["bikes"];

let state: LiveState = EMPTY_LIVE;
let source: EventSource | null = null;
let pending: LiveState | null = null;
const listeners = new Set<() => void>();

/** Changes arrive in bursts (a poll changes dozens of docks); repaint once per frame, not once per event. */
function update(next: LiveState) {
  const first = pending === null;
  pending = next;
  if (first) {
    requestAnimationFrame(() => {
      state = pending!;
      pending = null;
      listeners.forEach((l) => l());
    });
  }
}

function current(): LiveState {
  return pending ?? state;
}

function open() {
  source = new EventSource(`/live?topics=${TOPICS.join(",")}`);
  source.onopen = () => update({ ...current(), connected: true });
  source.onerror = () => update({ ...current(), connected: false }); // the browser retries after the server's retry:
  source.addEventListener("snapshot", (e) => update(applySnapshot(current(), JSON.parse(e.data) as SnapshotEvent)));
  source.addEventListener("status", (e) => update(applyStatus(current(), JSON.parse(e.data) as StatusEvent)));
  for (const topic of TOPICS) {
    source.addEventListener(topic, (e) => update(applyItem(current(), topic, JSON.parse(e.data) as ItemEvent)));
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (source === null) open();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      source?.close();
      source = null;
      state = EMPTY_LIVE;
    }
  };
}

/** The live state, re-rendering when it changes. Every component shares the tab's one stream. */
export function useLive(): LiveState {
  return useSyncExternalStore(subscribe, () => state, () => EMPTY_LIVE);
}
