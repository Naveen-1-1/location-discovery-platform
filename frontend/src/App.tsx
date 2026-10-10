import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { fetchCategories, fetchNearby, type NearbyPlace } from "./api";
import { CATEGORY_COLORS, FALLBACK_COLOR, formatDistance } from "./categories";
import { MapView } from "./MapView";

const BOSTON_COMMON = { lat: 42.355, lon: -71.0656 };
const RADII = [500, 1000, 1500, 3000, 5000];

export function App() {
  const [origin, setOrigin] = useState(BOSTON_COMMON);
  const [radius, setRadius] = useState(1500);
  const [chosen, setChosen] = useState<string[]>([]);
  const [selected, setSelected] = useState<NearbyPlace | null>(null);

  const categories = useQuery({ queryKey: ["categories"], queryFn: ({ signal }) => fetchCategories(signal) });
  const nearby = useQuery({
    queryKey: ["nearby", origin.lat, origin.lon, radius, chosen],
    queryFn: ({ signal }) => fetchNearby({ ...origin, radius, categories: chosen }, signal),
    placeholderData: (previous) => previous,
  });
  const places = nearby.data?.places ?? [];

  function toggle(code: string) {
    setChosen((cur) => (cur.includes(code) ? cur.filter((c) => c !== code) : [...cur, code]));
  }

  function useMyLocation() {
    navigator.geolocation.getCurrentPosition(
      (pos) => setOrigin({ lat: pos.coords.latitude, lon: pos.coords.longitude }),
      () => alert("Location unavailable; click the map to choose a starting point instead."),
    );
  }

  return (
    <div className="layout">
      <aside className="panel">
        <h1>Boston Places</h1>
        <p className="muted">Click the map to search from there.</p>

        <div className="row">
          <button onClick={useMyLocation}>Use my location</button>
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
        </div>

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
        <p className="muted">
          {nearby.isFetching ? "Searching…" : `${places.length} places, nearest first (straight-line distance)`}
        </p>

        <ol className="results">
          {places.map((p) => (
            <li key={p.id} className={selected?.id === p.id ? "active" : undefined} onClick={() => setSelected(p)}>
              <span className="dot" style={{ background: CATEGORY_COLORS[p.category] ?? FALLBACK_COLOR }} />
              <span className="name">{p.name}</span>
              <span className="dist">{formatDistance(p.distanceMeters)}</span>
              {p.address && <span className="addr">{p.address}</span>}
            </li>
          ))}
        </ol>
      </aside>

      <main className="map">
        <MapView
          origin={origin}
          radius={radius}
          places={places}
          selected={selected}
          onPickOrigin={(lat, lon) => {
            setSelected(null);
            setOrigin({ lat, lon });
          }}
          onSelect={setSelected}
        />
      </main>
    </div>
  );
}
