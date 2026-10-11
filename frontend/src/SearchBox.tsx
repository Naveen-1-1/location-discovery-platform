import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { fetchSearch } from "./api";
import { CATEGORY_COLORS, FALLBACK_COLOR, formatDistance } from "./categories";

type Props = {
  near: { lat: number; lon: number };
  onPlace: (place: { id: number; lat: number; lon: number }) => void;
  onAddress: (point: { lat: number; lon: number }) => void;
};

const DEBOUNCE_MS = 250;

/** Type a place's name or an address; choosing one moves the search there. Addresses come through our api. */
export function SearchBox({ near, onPlace, onAddress }: Props) {
  const [text, setText] = useState("");
  const [typed, setTyped] = useState("");
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setTyped(text.trim()), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [text]);
  // Rounded so small map moves reuse an answer; the server rounds further before asking Photon.
  const lat = Math.round(near.lat * 100) / 100;
  const lon = Math.round(near.lon * 100) / 100;
  const search = useQuery({
    queryKey: ["search", typed.toLowerCase(), lat, lon],
    queryFn: ({ signal }) => fetchSearch(typed, { lat, lon }, signal),
    enabled: typed.length >= 2,
    staleTime: 60_000,
  });
  const result = typed.length >= 2 ? search.data : undefined;
  const choose = (pick: () => void) => {
    pick();
    setOpen(false);
    setText("");
  };

  return (
    <div className="search" onKeyDown={(e) => e.key === "Escape" && setOpen(false)}>
      <input
        type="search"
        placeholder="Search places or addresses"
        aria-label="Search places or addresses"
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key !== "Enter" || !result) return;
          const place = result.places[0];
          const address = result.addresses[0];
          if (place) choose(() => onPlace(place));
          else if (address) choose(() => onAddress(address));
        }}
      />
      {open && result && (
        <div className="search-results" role="listbox">
          {result.places.map((p) => (
            <button key={`p${p.id}`} role="option" onClick={() => choose(() => onPlace(p))}>
              <span className="dot" style={{ background: CATEGORY_COLORS[p.category] ?? FALLBACK_COLOR }} />
              <span>
                {p.name}
                <span className="muted small"> {[p.address, formatDistance(p.distanceMeters)].filter(Boolean).join(" · ")}</span>
              </span>
            </button>
          ))}
          {result.addresses.map((a) => (
            <button key={`a${a.label}${a.lat}`} role="option" onClick={() => choose(() => onAddress(a))}>
              <span className="pin" aria-hidden>⌖</span>
              <span>{a.label}</span>
            </button>
          ))}
          {result.places.length === 0 && result.addresses.length === 0 && <p className="muted small">Nothing found.</p>}
          {result.addressStatus !== "ok" && result.addressStatus !== "too_short" && (
            <p className="muted small">Address search is unavailable right now; places only.</p>
          )}
          {result.addresses.length > 0 && (
            <p className="muted small">
              Addresses by <a href="https://photon.komoot.io">Photon</a>, © OpenStreetMap contributors
            </p>
          )}
        </div>
      )}
    </div>
  );
}
