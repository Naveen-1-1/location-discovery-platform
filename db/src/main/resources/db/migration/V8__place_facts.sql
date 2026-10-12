-- Phase 5: what the map's filters and a place's details read, one row per place, so a nearby search never
-- touches the records, licences or inspection history. Refreshed for the places a run touched when it completes, and
-- for any live place still without a row, so the first completion after this migration fills it for all places.

-- The latest inspection's failed checks: Cambridge reports only violations found, so a clean latest inspection is
-- one with none (Boston reports a pass or fail result itself).
ALTER TABLE place.inspection_summary ADD COLUMN latest_failed_checks int NOT NULL DEFAULT 0;
UPDATE place.inspection_summary s
SET latest_failed_checks = (
    SELECT count(v.line_hash) FILTER (WHERE v.status = 'Fail')
    FROM place.inspection i
    LEFT JOIN place.inspection_violation v ON v.inspection_id = i.inspection_id
    WHERE i.source = s.source AND i.source_key = s.source_key AND i.inspected_at = s.latest_at);

CREATE TABLE place.place_facts (
    place_id          bigint      PRIMARY KEY REFERENCES place.place (place_id),
    accepts_snap      boolean     NOT NULL,   -- a live USDA SNAP retailer record is linked
    active_licence    boolean     NOT NULL,   -- a live, current Boston or Cambridge food or premises licence is linked
    inspection        text        CHECK (inspection IN ('passed', 'failed', 'other')),   -- the latest inspection
    inspected_at      timestamptz,
    failed_checks_12m int,
    park_features     text,                   -- "Basketball:2;Playground:1" from Boston's park features
    hours             text,                   -- the regular week, for display: "Mon–Fri 07:00–23:00; Sat closed"
    hours_source      text        CHECK (hours_source IN ('osm', 'cambridge_permit', 'boston_licence')),
    open_week         int4multirange,         -- the same week as minutes since Monday 00:00, Boston time
    refreshed_at      timestamptz NOT NULL DEFAULT now()
);
