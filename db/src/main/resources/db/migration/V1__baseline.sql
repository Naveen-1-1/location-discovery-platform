-- Baseline schema (Phase 1). Later phases add migrations; never edit an applied one.

CREATE EXTENSION IF NOT EXISTS postgis;   -- geography, KNN (<->), ST_DWithin
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- fuzzy place-name search (Phase 5)
CREATE EXTENSION IF NOT EXISTS unaccent;  -- "Café" matches "cafe" (Phase 5)
-- h3 / h3_postgis are enabled in the migration that first uses them (Phase 3).

CREATE SCHEMA IF NOT EXISTS place;   -- places and their sources
CREATE SCHEMA IF NOT EXISTS ingest;  -- import runs, checkpoints, rejects (Phase 2)
CREATE SCHEMA IF NOT EXISTS app;     -- users, lists, sessions (Phase 6)

-- App categories (data/categories.yaml). Codes are stable; labels are for display.
CREATE TABLE place.category (
    code       text PRIMARY KEY,
    label      text NOT NULL,
    sort_order int  NOT NULL
);

INSERT INTO place.category (code, label, sort_order) VALUES
    ('food',             'Food & drink',          10),
    ('grocery',          'Groceries',             20),
    ('pharmacy',         'Pharmacies',            30),
    ('gym',              'Gyms & fitness',        40),
    ('park',             'Parks',                 50),
    ('bank',             'Banks & credit unions', 60),
    ('atm',              'ATMs',                  70),
    ('ev_charging',      'EV charging',           80),
    ('bike_share',       'Bike share',            90),
    ('transit',          'Transit stops',        100),
    ('library',          'Libraries',            110),
    ('hospital',         'Hospitals',            120),
    ('community_centre', 'Community centres',    130);

-- One row per real-world place, whichever sources describe it. Rows are retired, never deleted,
-- so saved lists keep working.
CREATE TABLE place.place (
    place_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text                   NOT NULL,
    category   text                   NOT NULL REFERENCES place.category (code),
    geom       geography(Point, 4326) NOT NULL,
    address    text,
    phone      text,
    website    text,
    created_at timestamptz            NOT NULL DEFAULT now(),
    updated_at timestamptz            NOT NULL DEFAULT now(),
    retired_at timestamptz
);

-- Partial indexes: queries must say "retired_at IS NULL" to use them.
CREATE INDEX place_geom_live_gist ON place.place USING gist (geom) WHERE retired_at IS NULL;
CREATE INDEX place_category_live ON place.place (category) WHERE retired_at IS NULL;

-- Which source record(s) a place came from. The (source, source_key) pair is the idempotency key for imports.
CREATE TABLE place.place_source_link (
    source     text   NOT NULL,
    source_key text   NOT NULL,
    place_id   bigint NOT NULL REFERENCES place.place (place_id),
    PRIMARY KEY (source, source_key)
);

CREATE INDEX place_source_link_place ON place.place_source_link (place_id);
