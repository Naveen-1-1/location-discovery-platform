// Live data from live-hub over one Server-Sent Events stream per browser tab. In development Vite proxies
// /live to localhost:8081. The stream starts with a snapshot of every topic it asks for, then sends only what
// changed; after a reconnect, a few seconds later, the server sends a fresh snapshot, so nothing needs replaying.
// Components say which topics they need; the stream reopens when that set changes.
import { useEffect, useSyncExternalStore } from "react";
import ReconnectingEventSource from "reconnecting-eventsource";

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

export type StopAlert = { id: string; effect: string | null; detail: string | null; severity: string | null; header: string | null };

/** The MBTA alerts in effect at one stop or station. */
export type StopAlerts = { id: string; elevator: boolean; escalator: boolean; alerts: StopAlert[] };

export type Vehicle = {
  id: string;
  route: string | null;
  label: string | null;
  lat: number;
  lon: number;
  bearing: number | null;
  status: string | null;
  stop: string | null;
  updated: number;
};

export type LiveItem = DockStatus | StopAlerts | Vehicle;

export type TopicHealth = { stale: boolean; updated: number | null };

export type LiveState = {
  /** Whether the stream is open right now; false while the browser reconnects. */
  connected: boolean;
  /** Per topic ("bikes"), whether its feed is out of date. Missing until the first snapshot. */
  topics: Readonly<Record<string, TopicHealth>>;
  /** Live key ("bikes:<station_id>", the api's liveKey) → the item. */
  items: ReadonlyMap<string, LiveItem>;
};

export const EMPTY_LIVE: LiveState = { connected: false, topics: {}, items: new Map() };

type SnapshotEvent = { topic: string; stale: boolean; updated: number | null; items: LiveItem[] };
type StatusEvent = { topic: string; stale: boolean; updated: number | null };
type ItemEvent = (LiveItem & { removed?: false }) | { id: string; removed: true };

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

/** "Elevator out · 2 alerts": the outages first, because they decide whether someone can use the station at all. */
export function describeAlerts(a: StopAlerts): string {
  const parts = [a.elevator && "Elevator out", a.escalator && "Escalator out"].filter(Boolean) as string[];
  const others = a.alerts.filter((x) => x.detail !== "ELEVATOR_CLOSURE" && x.detail !== "ESCALATOR_CLOSURE").length;
  if (others > 0) parts.push(`${others} ${others === 1 ? "alert" : "alerts"}`);
  return parts.join(" · ");
}

function docksText(docks: number): string {
  return `${docks} free ${docks === 1 ? "dock" : "docks"}`;
}

// ---- The one stream per tab ----

let state: LiveState = EMPTY_LIVE;
let source: ReconnectingEventSource | null = null;
let openTopics = "";
let pending: LiveState | null = null;
const listeners = new Set<() => void>();
/** How many mounted components want each topic. */
const wanted = new Map<string, number>();

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

/** (Re)opens the stream for exactly the wanted topics; closes it when none are wanted. */
function reconcile() {
  const topics = [...wanted.keys()].sort().join(",");
  if (topics === openTopics) return;
  source?.close();
  source = null;
  openTopics = topics;
  const keep = new Set(wanted.keys());
  update({
    connected: false,
    topics: Object.fromEntries(Object.entries(current().topics).filter(([t]) => keep.has(t))),
    items: new Map([...current().items].filter(([key]) => keep.has(key.slice(0, key.indexOf(":"))))),
  });
  if (topics === "") return;
  // Unlike a plain EventSource, this one also comes back after an error answer, such as a proxy's 502 while live-hub
  // restarts. It waits a random time up to 7 s, about the spread the server asks for, so a restart does not bring
  // every tab back at once.
  const es = new ReconnectingEventSource(`/live?topics=${topics}`, { max_retry_time: 7_000 });
  source = es;
  es.onopen = () => update({ ...current(), connected: true });
  es.onerror = () => update({ ...current(), connected: false });
  es.addEventListener("snapshot", (e) => update(applySnapshot(current(), JSON.parse(e.data) as SnapshotEvent)));
  es.addEventListener("status", (e) => update(applyStatus(current(), JSON.parse(e.data) as StatusEvent)));
  for (const topic of wanted.keys()) {
    es.addEventListener(topic, (e) => update(applyItem(current(), topic, JSON.parse(e.data) as ItemEvent)));
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * The live state for {@code topics}, re-rendering when it changes. Every component shares the tab's one stream, which
 * carries the union of what mounted components ask for.
 */
export function useLive(topics: readonly string[]): LiveState {
  const key = [...topics].sort().join(",");
  useEffect(() => {
    const mine = key === "" ? [] : key.split(",");
    for (const t of mine) wanted.set(t, (wanted.get(t) ?? 0) + 1);
    reconcile();
    return () => {
      for (const t of mine) {
        const n = (wanted.get(t) ?? 1) - 1;
        if (n === 0) wanted.delete(t);
        else wanted.set(t, n);
      }
      // After this render's effects settle, so a topic set that merely changed reopens the stream once, not twice.
      queueMicrotask(reconcile);
    };
  }, [key]);
  return useSyncExternalStore(subscribe, () => state, () => EMPTY_LIVE);
}
