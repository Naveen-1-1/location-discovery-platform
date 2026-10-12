# How this release was made

Boston place-discovery map: places in Boston, Cambridge, Somerville and Brookline, release {{date}}. {{places}} places
built from {{records}} source records. Licence: ODbL 1.0 (`LICENSE`); credits in `ATTRIBUTION.md`. The code and the
reasoning behind every rule below (the ADRs) are at https://github.com/Naveen-1-1/place-discovery-map.

## Files
- `places.parquet`: one row per place, GeoParquet 1.0 (a point in WGS 84 longitude and latitude, WKB, in `geometry`).
- `place_sources.parquet`: one row per source record that went into a place.
- `LICENSE`, `ATTRIBUTION.md`, this file, `licences/` (texts some sources require) and `SHA256SUMS`.

### `places.parquet`
| Column | Meaning |
|---|---|
| `place_id` | Stable id. A place keeps it for good; one that closes is left out of later releases, and comes back under the same id if a source lists it again |
| `name`, `category`, `address`, `phone`, `website` | From the place's best-ranked source record that has the value (see "Merging") |
| `accepts_snap` | A USDA SNAP retailer record is linked to the place |
| `active_licence` | An active Boston food or Licensing Board licence, or a Cambridge food permit issued in the last 13 months |
| `hours`, `hours_source` | The regular week, readable (`Mon–Fri 07:00–23:00; Sat closed`), from OSM `opening_hours` (`osm`), else Cambridge's permit (`cambridge_permit`), else Boston's licensed hours (`boston_licence`, the latest the premises may be open). Holidays and seasons are left out; unreadable hours are empty, never "closed" |
| `park_features` | Boston's park features, as `kind:count;…` |
| `inspection`, `inspected_at` | The latest food inspection of the place's records: `passed`, `failed` or `other`. Boston: `HE_Pass` passed; `HE_Fail…`, `HE_Closure`, `HE_Hearing`, `HE_TSOP` failed. Cambridge: a completed inspection that found no violations passed |
| `inspection_source`, `inspection_record_id` | The record that inspection belongs to, to join the full history from the city's own open data (below) |
| `geometry` | The place's point |

### `place_sources.parquet`
| Column | Meaning |
|---|---|
| `place_id` | The place |
| `source` | The source's id, as in `ATTRIBUTION.md` |
| `record_id` | The record's id at its source: OSM `node/…`, `way/…` or `relation/…`; the Overture (GERS) id; for Boston's food inspections and Licensing Board, the licence number; for Cambridge's inspections, `business name|address`; for Cambridge's permits, the permit id; for Boston's active food licences, `property id|business|category|address`; NPPES, the organisation's NPI; FDIC, the branch's `UNINUM`; AFDC, the station id |
| `link` | How the record joined the place: `created` (it made the place), `id` (a shared identifier), `score` (the matcher's score), `corroborated` (a low-confidence Overture record confirmed by another source) |
| `score` | The matcher's score, for `score` links |

**Inspection history:** this release carries only each place's latest inspection. The full history is open data:
Boston's "Food Establishment Inspections" (join `licenseno` to `record_id` where `source` is
`boston_food_inspections`) and Cambridge's "Sanitary Inspections" (join `business_name|full_address` for
`cambridge_sanitary_inspections`).

## Which records are in
Only sources whose terms allow redistribution under ODbL (`redistribution: "yes"` in the project's
`data/sources.yaml`, a licence audit from 2026-10-10 that is not legal advice). Left out:
- **MBTA and Bluebikes** (transit stops and bike docks): their licences allow showing the data in an app but not
  re-licensing it, so the map shows them and this release does not. No value from them is in any place.
- **WIC and HIP vendor lists:** not imported at all.
- **Personal data:** owner names and personal phone numbers are never downloaded from the publishers in the first
  place. Inspectors' comments are free text that can name people, so they are not released either.
- **Borderline matches** (scores 0.60–0.81) stay unlinked, in the project's review list.

## Merging
1. **Existence:** for each category, only some sources may say a place exists (for example AFDC for EV charging,
   MassGIS for hospitals, FDIC, Overture and OSM for banks); others only add details. An Overture place stands on its
   own only with confidence 0.9 or more; below that it needs a nearby record of the same kind from another source.
2. **Matching:** a record first joins a place through a shared identifier. Otherwise it is compared with places of a
   compatible kind (the same category, food with grocery, grocery with pharmacy, or any place for a record without a
   category) within 100 m, by `0.45·name + 0.25·address + 0.2·distance + 0.1·category`. At 0.81 or more it links; at
   0.60–0.81 it waits for review; below, an existence source's record creates a new place. Park parcels are compared by
   their outline, not their point.
3. **Values:** each place takes its name, category, point, address, phone and website from its best-ranked record
   that has one, with the category's ranking of sources (bank names come from Overture or OSM first, not FDIC's legal
   names).

For this release the merge was run again over the open records alone, so a place is in only if an open existence
source lists it, and every value comes from an open record.

**How good the matching is:** on 400 labelled cases (2026-10-11), linking at 0.81 has a precision of 0.957 and a
recall of 0.860. The labels were made by an AI assistant and checked by no person. The wrong links found were all
different businesses at the same address.

## Coordinates
Sources are asked for WGS 84 where they can give it; Boston's licences (EPSG:2249) and MassGIS data (EPSG:26986)
are reprojected. Records without coordinates (pharmacies, some licences) are placed with Photon (OpenStreetMap data),
accepted only when the house number and ZIP code match, the street matches closely and the point is inside the four
towns; the others stay unplaced and are not in this release.

## Counts in this release
{{by_category}}

{{by_source}}
