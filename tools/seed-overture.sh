#!/usr/bin/env bash
# Phase 1 temporary seed: loads Overture places into Neon so the map has something to show.
# Usage: tools/dev.sh seed   (or run directly with PG* variables set)
# Idempotent: re-running updates existing rows (matched by Overture id) and retires ones that disappeared.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f spikes/phase0/work/overture_bbox.parquet ]]; then
  echo "Extracting Overture places for the area (one-off, a few seconds)..."
  mkdir -p spikes/phase0/work
  duckdb -batch spikes/phase0/work/spike.duckdb < spikes/phase0/sql/10_overture_extract.sql > /dev/null
fi

mkdir -p data/cache
duckdb -batch -box < tools/seed-overture.sql   # -batch: no interactive terminal probing

psql -X -v ON_ERROR_STOP=1 -q <<'SQL'
BEGIN;

CREATE TEMP TABLE seed (
    overture_id text PRIMARY KEY, name text, category text, lon float8, lat float8,
    address text, phone text, website text
) ON COMMIT DROP;
\copy seed FROM 'data/cache/overture_seed.csv' WITH (FORMAT csv, HEADER true)

-- Existing places: refresh their attributes (and un-retire them if they came back).
UPDATE place.place p
SET name = s.name, category = s.category,
    geom = ST_SetSRID(ST_MakePoint(s.lon, s.lat), 4326)::geography,
    address = s.address, phone = s.phone, website = s.website,
    updated_at = now(), retired_at = NULL
FROM seed s
JOIN place.place_source_link l ON l.source = 'overture_seed' AND l.source_key = s.overture_id
WHERE p.place_id = l.place_id;

-- New places: take ids from the identity sequence first so the link rows can reference them.
CREATE TEMP TABLE seed_new ON COMMIT DROP AS
SELECT s.*, nextval(pg_get_serial_sequence('place.place', 'place_id')) AS place_id
FROM seed s
WHERE NOT EXISTS (SELECT 1 FROM place.place_source_link l
                  WHERE l.source = 'overture_seed' AND l.source_key = s.overture_id);

INSERT INTO place.place (place_id, name, category, geom, address, phone, website)
OVERRIDING SYSTEM VALUE
SELECT place_id, name, category, ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography, address, phone, website
FROM seed_new;

INSERT INTO place.place_source_link (source, source_key, place_id)
SELECT 'overture_seed', overture_id, place_id FROM seed_new;

-- Gone from the seed: retire, never delete (ADR 0020).
UPDATE place.place p SET retired_at = now(), updated_at = now()
FROM place.place_source_link l
WHERE l.place_id = p.place_id AND l.source = 'overture_seed' AND p.retired_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM seed s WHERE s.overture_id = l.source_key);

COMMIT;
ANALYZE place.place;
SQL

psql -X -A -t -c "SELECT 'live places in database: ' || count(*) FROM place.place WHERE retired_at IS NULL;"
