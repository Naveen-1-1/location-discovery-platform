import { useQuery } from "@tanstack/react-query";
import { useCombobox } from "downshift";
import { useState } from "react";
import { useDebounce } from "use-debounce";
import { fetchSearch, type SearchResult } from "./api";
import { CATEGORY_COLORS, FALLBACK_COLOR, formatDistance } from "./categories";

type Props = {
  near: { lat: number; lon: number };
  onPlace: (place: { id: number; lat: number; lon: number }) => void;
  onAddress: (point: { lat: number; lon: number }) => void;
};

type Option =
  | { kind: "place"; place: SearchResult["places"][number] }
  | { kind: "address"; address: SearchResult["addresses"][number] };

const DEBOUNCE_MS = 250;

/**
 * Type a place's name or an address; choosing one moves the search there. Addresses come through our api. Downshift
 * gives the list its keyboard use (arrows, Enter, Escape) and its screen-reader roles.
 */
export function SearchBox({ near, onPlace, onAddress }: Props) {
  const [text, setText] = useState("");
  const [typed] = useDebounce(text.trim(), DEBOUNCE_MS);
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
  const options: Option[] = result
    ? [
        ...result.places.map((place): Option => ({ kind: "place", place })),
        ...result.addresses.map((address): Option => ({ kind: "address", address })),
      ]
    : [];

  const { isOpen, highlightedIndex, getInputProps, getMenuProps, getItemProps } = useCombobox<Option>({
    items: options,
    inputValue: text,
    selectedItem: null, // nothing stays selected: choosing moves the search and clears the box
    itemToString: () => "",
    defaultHighlightedIndex: 0, // Enter takes the first match
    onInputValueChange: ({ inputValue }) => setText(inputValue ?? ""),
    onSelectedItemChange: ({ selectedItem }) => {
      if (selectedItem?.kind === "place") onPlace(selectedItem.place);
      else if (selectedItem?.kind === "address") onAddress(selectedItem.address);
    },
  });

  return (
    <div className="search">
      <input type="search" placeholder="Search places or addresses" {...getInputProps({ "aria-label": "Search places or addresses" })} />
      <div className="search-results" hidden={!(isOpen && result)}>
        <ul {...getMenuProps({ "aria-label": "Places and addresses" })}>
          {isOpen &&
            options.map((option, index) => (
              <li
                key={option.kind === "place" ? `p${option.place.id}` : `a${option.address.label}${option.address.lat}`}
                className={highlightedIndex === index ? "active" : undefined}
                {...getItemProps({ item: option, index })}
              >
                {option.kind === "place" ? (
                  <>
                    <span className="dot" style={{ background: CATEGORY_COLORS[option.place.category] ?? FALLBACK_COLOR }} />
                    <span>
                      {option.place.name}
                      <span className="muted small">
                        {" "}
                        {[option.place.address, formatDistance(option.place.distanceMeters)].filter(Boolean).join(" · ")}
                      </span>
                    </span>
                  </>
                ) : (
                  <>
                    <span className="pin" aria-hidden>⌖</span>
                    <span>{option.address.label}</span>
                  </>
                )}
              </li>
            ))}
        </ul>
        {result && options.length === 0 && <p className="muted small">Nothing found.</p>}
        {result && result.addressStatus !== "ok" && result.addressStatus !== "too_short" && (
          <p className="muted small">Address search is unavailable right now; places only.</p>
        )}
        {result && result.addresses.length > 0 && (
          <p className="muted small">
            Addresses by <a href="https://photon.komoot.io">Photon</a>, © OpenStreetMap contributors
          </p>
        )}
      </div>
    </div>
  );
}
