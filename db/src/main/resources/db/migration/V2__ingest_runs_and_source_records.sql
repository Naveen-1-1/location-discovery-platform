-- Phase 2: import bookkeeping and per-source records.
-- Matching tables (review queue, link provenance) arrive with the matcher in the next migration.

-- One row per import attempt of one source. At most one unfinished run per source; the importer resumes it.
CREATE TABLE ingest.run (
    run_id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source          text        NOT NULL,                  -- id in data/sources.yaml
    status          text        NOT NULL DEFAULT 'FETCHING'
                    CHECK (status IN ('FETCHING', 'RUNNING', 'COMPLETE', 'UNCHANGED', 'FAILED')),
    snapshot_sha256 text,                                  -- of the saved input; batches are cut from it
    total_rows      int,
    total_batches   int,
    rejected_rows   int,                                   -- set at completion
    result_checksum text,                                  -- md5 of the source's live records at completion
    attempts        int         NOT NULL DEFAULT 1,        -- incremented on each resume
    error           text,
    started_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz
);

CREATE UNIQUE INDEX run_one_open_per_source ON ingest.run (source) WHERE status IN ('FETCHING', 'RUNNING');
CREATE INDEX run_by_source ON ingest.run (source, run_id DESC);

-- Checkpoint, written in the same transaction as the batch's records: a batch is either fully applied and listed
-- here, or not applied at all and redone on resume.
CREATE TABLE ingest.batch_done (
    run_id   bigint      NOT NULL REFERENCES ingest.run (run_id),
    seq      int         NOT NULL,
    rows     int         NOT NULL,
    rejected int         NOT NULL,
    checksum text        NOT NULL,   -- sha-256 of the batch's input rows
    keys     text[],                 -- source keys accepted in this batch; read at completion, then cleared
    done_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);

-- Rows that failed validation, kept for inspection. Inserted with their batch, so a redone batch cannot duplicate
-- them. Personal fields are never downloaded, so they cannot end up here.
CREATE TABLE ingest.reject (
    run_id bigint NOT NULL REFERENCES ingest.run (run_id),
    seq    int    NOT NULL,
    row_no int    NOT NULL,   -- position within the batch
    reason text   NOT NULL,
    record jsonb  NOT NULL,
    PRIMARY KEY (run_id, seq, row_no)
);

-- What one source says about one place. Imports upsert on (source, source_key) and skip rows whose
-- content hash is unchanged; matching links records to places through place.place_source_link.
-- Records missing from a completed run are retired, never deleted.
CREATE TABLE place.source_record (
    source      text   NOT NULL,
    source_key  text   NOT NULL,
    name        text   NOT NULL,
    category    text   REFERENCES place.category (code),   -- null: the source's own class maps to no app category
    geom        geography(Point, 4326),                     -- null: the source gives no location (geocoded later)
    address     text,
    postcode    text,
    phone       text,
    website     text,
    attrs       jsonb  NOT NULL DEFAULT '{}',               -- source-specific fields for enrichment and matching
    row_hash    text   NOT NULL,                            -- sha-256 of the columns above, computed by the importer
    created_run bigint NOT NULL REFERENCES ingest.run (run_id),
    changed_run bigint NOT NULL REFERENCES ingest.run (run_id),
    retired_at  timestamptz,
    PRIMARY KEY (source, source_key)
);

CREATE INDEX source_record_geom_live ON place.source_record USING gist (geom) WHERE retired_at IS NULL;
