import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { fetchCategories, fetchIsochrones, fetchNearby, fetchRoute, type NearbyPlace, type TravelMode } from "./api";
import {
  BIKE_SHARE_CREDIT,
  CATEGORY_COLORS,
  FALLBACK_COLOR,
  formatDistance,
  formatDuration,
  ISOCHRONE_MINUTES,
  MODE_LABELS,
  MODE_VERBS,
  TRANSIT_CREDIT,
} from "./categories";
import { useLive, type Vehicle } from "./live";
import { LiveLine, liveFor } from "./LiveLine";
import { MapView } from "./MapView";

const BOSTON_COMMON = { lat: 42.355, lon: -71.0656 };
const RADII = [500, 1000, 1500, 3000, 5000];
const MODES: TravelMode[] = ["walk", "bike", "drive"];

export function App() {
  const [origin, setOrigin] = useState(BOSTON_COMMON);
  /** Null: nearest by straight-line distance within radius. */
  const [mode, setMode] = useState<TravelMode | null>("walk");
  const [radius, setRadius] = useState(1500);
  const [chosen, setChosen] = useState<string[]>([]);
  /** By id: the place's travel time comes from whichever result is on screen, never from an older one. */
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [routeTo, setRouteTo] = useState<number | null>(null);
  const [showArea, setShowArea] = useState(false);
  const [showVehicles, setShowVehicles] = useState(false);

  const categories = useQuery({ queryKey: ["categories"], queryFn: ({ signal }) => fetchCategories(signal) });
  const nearby = useQuery({
    queryKey: ["nearby", origin.lat, origin.lon, mode, mode ? null : radius, chosen],
    queryFn: ({ signal }) => fetchNearby({ ...origin, mode, radius, categories: chosen }, signal),
    placeholderData: (previous) => previous,
  });
  const route = useQuery({
    queryKey: ["route", origin.lat, origin.lon, routeTo, mode],
    queryFn: ({ signal }) => fetchRoute(origin, routeTo!, mode!, signal),
    enabled: routeTo !== null && mode !== null,
  });
  const area = useQuery({
    queryKey: ["isochrone", origin.lat, origin.lon, mode],
    queryFn: ({ signal }) => fetchIsochrones(origin, mode!, ISOCHRONE_MINUTES, signal),
    enabled: showArea && mode !== null,
  });

  const live = useLive(showVehicles ? ["bikes", "alerts", "vehicles"] : ["bikes", "alerts"]);
  const vehicles = showVehicles
    ? [...live.items].filter(([key]) => key.startsWith("vehicles:")).map(([, v]) => v as Vehicle)
    : [];
  const result = nearby.data;
  const places = result?.places ?? [];
  const selected = places.find((p) => p.id === selectedId) ?? null;
  const routed = result?.mode != null && result.fallback === null;
  const credits = [
    ...(places.some((p) => p.category === "bike_share") ? [{ id: "bike-share-credit", html: BIKE_SHARE_CREDIT }] : []),
    ...(places.some((p) => p.category === "transit") || vehicles.length > 0
      ? [{ id: "transit-credit", html: TRANSIT_CREDIT }]
      : []),
  ];
  const liveOf = (p: NearbyPlace) => liveFor(p, live, result);

  function toggle(code: string) {
    setChosen((cur) => (cur.includes(code) ? cur.filter((c) => c !== code) : [...cur, code]));
  }

  function moveTo(lat: number, lon: number) {
    setSelectedId(null);
    setRouteTo(null);
    setOrigin({ lat, lon });
  }

  function chooseMode(next: TravelMode | null) {
    setRouteTo(null);
    setMode(next);
  }

  function useMyLocation() {
    navigator.geolocation.getCurrentPosition(
      (pos) => moveTo(pos.coords.latitude, pos.coords.longitude),
      () => alert("Location unavailable; click the map to choose a starting point instead."),
    );
  }

  return (
    <div className="layout">
      <aside className="panel">
        <h1>Boston Places</h1>
        <p className="muted">Click the map to search from there.</p>

        <div className="segmented" role="radiogroup" aria-label="Sort by">
          {MODES.map((m) => (
            <button key={m} role="radio" aria-checked={mode === m} className={mode === m ? "on" : undefined}
              onClick={() => chooseMode(m)}>
              {MODE_LABELS[m]}
            </button>
          ))}
          <button role="radio" aria-checked={mode === null} className={mode === null ? "on" : undefined}
            onClick={() => chooseMode(null)}>
            Distance
          </button>
        </div>

        <div className="row">
          <button onClick={useMyLocation}>Use my location</button>
          {mode === null ? (
            <label>
              Within{" "}
              <select value={radius} onChange={(e) => setRadius(Number(e.target.value))}>
                {RADII.map((r) => (
                  <option key={r} value={r}>
                    {formatDistance(r)}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <label className="toggle">
              <input type="checkbox" checked={showArea} onChange={(e) => setShowArea(e.target.checked)} />
              Show reachable area
            </label>
          )}
        </div>

        <label className="toggle">
          <input type="checkbox" checked={showVehicles} onChange={(e) => setShowVehicles(e.target.checked)} />
          Show buses and trains
        </label>

        <div className="chips" role="group" aria-label="Categories">
          {categories.data?.map((c) => (
            <button
              key={c.code}
              className={chosen.includes(c.code) ? "chip on" : "chip"}
              style={{ borderColor: CATEGORY_COLORS[c.code] ?? FALLBACK_COLOR }}
              aria-pressed={chosen.includes(c.code)}
              onClick={() => toggle(c.code)}
            >
              {c.label}
            </button>
          ))}
        </div>

        {nearby.isError && <p className="error">Could not load places: {(nearby.error as Error).message}</p>}
        {area.isError && <p className="error">Reachable area unavailable: {(area.error as Error).message}</p>}
        <SearchSummary searching={nearby.isFetching} count={places.length} mode={result?.mode ?? null}
          routed={routed} fallback={result?.fallback ?? null} snapMeters={result?.maxSnapErrorMeters}
          withoutTraffic={result?.driveWithoutTraffic ?? false} />
        {result?.live?.status === "unavailable" && !live.connected && (
          <p className="muted warn">Live bike counts are unavailable right now.</p>
        )}

        <ol className="results">
          {places.map((p) => (
            <li key={p.id} className={selectedId === p.id ? "active" : undefined} onClick={() => setSelectedId(p.id)}>
              <span className="dot" style={{ background: CATEGORY_COLORS[p.category] ?? FALLBACK_COLOR }} />
              <span className="name">{p.name}</span>
              <span className="dist">
                {p.travelSeconds !== null ? formatDuration(p.travelSeconds) : formatDistance(p.distanceMeters)}
              </span>
              {(p.address || p.travelSeconds !== null) && (
                <span className="addr">
                  {[p.address, p.travelSeconds !== null ? `${formatDistance(p.distanceMeters)} away` : null]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              )}
              <LiveLine live={liveOf(p)} />
            </li>
          ))}
        </ol>
      </aside>

      <main className="map">
        <MapView
          origin={origin}
          radius={mode === null ? radius : 2500}
          places={places}
          selected={selected}
          mode={mode}
          resultMode={result?.mode ?? null}
          route={routeTo === selected?.id ? route.data : undefined}
          routeState={route.isFetching ? "loading" : route.isError ? (route.error as Error).message : null}
          isochrones={showArea && mode !== null ? area.data : undefined}
          routingCredit={routed || (showArea && area.data !== undefined) || route.data !== undefined}
          credits={credits}
          vehicles={vehicles}
          selectedLive={selected ? liveOf(selected) : null}
          onPickOrigin={moveTo}
          onSelect={(p) => {
            setSelectedId(p?.id ?? null);
            if (p?.id !== routeTo) setRouteTo(null);
          }}
          onShowRoute={(p) => setRouteTo(p.id)}
        />
      </main>
    </div>
  );
}

type SummaryProps = {
  searching: boolean;
  count: number;
  mode: TravelMode | null;
  routed: boolean;
  fallback: string | null;
  snapMeters: number | undefined;
  withoutTraffic: boolean;
};

/** What the list is sorted by, and the caveats that come with it. */
function SearchSummary({ searching, count, mode, routed, fallback, snapMeters, withoutTraffic }: SummaryProps) {
  if (searching) return <p className="muted">Searching…</p>;
  if (mode === null) return <p className="muted">{count} places, nearest first (straight-line distance)</p>;
  if (!routed) {
    return (
      <p className="muted warn">
        {count} places, nearest first in a straight line: {MODE_VERBS[mode]} times are unavailable right now
        {fallback ? ` (${fallback.replace("_", " ")})` : ""}.
      </p>
    );
  }
  return (
    <p className="muted">
      {count} places, quickest first by {MODE_VERBS[mode]} time
      {withoutTraffic ? " (without traffic)" : ""}. Times are from within about {snapMeters} m of your point.
    </p>
  );
}
