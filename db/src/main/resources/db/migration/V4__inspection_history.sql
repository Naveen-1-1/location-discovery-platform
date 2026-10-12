-- Phase 2, step 5: skipping unchanged downloads, and inspection history.

-- A marker of the publisher's data read before downloading (a last-modified time, an ETag). When it matches the
-- last successful run, the download is skipped.
ALTER TABLE ingest.run ADD COLUMN source_version text;

-- Inspection history, stored compactly. The inspected establishment is the source's record in
-- place.source_record (source, source_key), so it is matched to places like any other record; the history hangs
-- off that key. History is kept even when the establishment's record is retired.
CREATE TABLE place.inspection (
    inspection_id bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source        text        NOT NULL,
    source_key    text        NOT NULL,
    inspected_at  timestamptz NOT NULL,
    result        text        NOT NULL,
    created_run   bigint      NOT NULL REFERENCES ingest.run (run_id),
    -- Boston has inspections with two results at the same time, so the result is part of the identity.
    UNIQUE (source, source_key, inspected_at, result)
);

-- Violation descriptions repeat on every line in the source; stored once per code.
CREATE TABLE place.violation_code (
    source      text NOT NULL,
    code        text NOT NULL,
    description text,
    PRIMARY KEY (source, code)
);

CREATE TABLE place.inspection_violation (
    inspection_id bigint      NOT NULL REFERENCES place.inspection (inspection_id),
    line_hash     bytea       NOT NULL,   -- md5 of the line's fields: identical lines collapse, re-imports never duplicate
    code          text,
    level         smallint    CHECK (level BETWEEN 1 AND 3),
    status        text,                   -- Pass / Fail
    cited_at      timestamptz,
    status_date   timestamptz,
    comment       text        CHECK (length(comment) <= 280),
    PRIMARY KEY (inspection_id, line_hash)
);

-- What the map's filters read, so map queries never touch the history tables. Rebuilt for a source
-- whenever its import completes.
CREATE TABLE place.inspection_summary (
    source                text        NOT NULL,
    source_key            text        NOT NULL,
    latest_result         text        NOT NULL,
    latest_at             timestamptz NOT NULL,
    inspections           int         NOT NULL,
    failed_checks_12m     int         NOT NULL,   -- violation lines marked Fail in the last 12 months
    PRIMARY KEY (source, source_key)
);
