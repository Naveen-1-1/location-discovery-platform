// The next MBTA departures at a stop or station, shown in its popup. Departures are not streamed (every stop together
// is about 2 MB), so this asks live-hub for the one stop on screen, and again every 20 s while it stays open.
import { useQuery } from "@tanstack/react-query";
import type { StopAlerts } from "./live";

type Departure = { route: string; routeName: string; direction: number; destination: string | null; time: number };
type DeparturesResponse = {
  stop: string;
  stale: boolean;
  updated: number | null;
  departures: { id: string; next: Departure[] } | null;
};

async function fetchDepartures(stop: string, signal: AbortSignal): Promise<DeparturesResponse> {
  const res = await fetch(`/live/departures?stop=${encodeURIComponent(stop)}`, { signal });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return (await res.json()) as DeparturesResponse;
}

export function minutesUntil(epochSeconds: number, nowMillis: number): string {
  const minutes = Math.round((epochSeconds * 1000 - nowMillis) / 60_000);
  return minutes <= 0 ? "now" : `${minutes} min`;
}

export function Departures({ stop, alerts }: { stop: string; alerts: StopAlerts | null }) {
  const q = useQuery({
    queryKey: ["departures", stop],
    queryFn: ({ signal }) => fetchDepartures(stop, signal),
    // The feed only runs while someone looks; its first answer after a quiet spell lands a few seconds later.
    refetchInterval: (query) => (query.state.data?.stale ? 4_000 : 20_000),
  });
  const next = q.data?.departures?.next.slice(0, 5) ?? [];
  return (
    <div className="departures">
      {alerts?.alerts.map((a) => a.header && <div key={a.id} className="warn">{a.header}</div>)}
      {q.isError && <div className="error">Departures unavailable</div>}
      {q.data && (q.data.stale && next.length === 0 ? (
        <div className="muted">Loading departures…</div>
      ) : next.length === 0 ? (
        <div className="muted">No departures soon</div>
      ) : (
        <ul>
          {next.map((d) => (
            <li key={`${d.route}-${d.direction}-${d.time}`}>
              <span>{d.routeName}{d.destination ? ` to ${d.destination}` : ""}</span>
              <span className="muted">{minutesUntil(d.time, Date.now())}</span>
            </li>
          ))}
        </ul>
      ))}
    </div>
  );
}
