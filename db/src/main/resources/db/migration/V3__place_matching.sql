-- Phase 2, step 4: Overture and OSM imports and record matching (ADR 0022, 0032).

-- Rows that are valid but out of scope (outside the four towns, no app category, permanently closed). They are not
-- stored and do not count toward the 2% reject limit.
ALTER TABLE ingest.batch_done ADD COLUMN skipped int NOT NULL DEFAULT 0;

-- How each record came to belong to its place:
--   id            joined on a shared identifier
--   score         the rule-based score reached 0.80
--   corroborated  a low-confidence Overture place confirmed by another source's place nearby (ADR 0032)
--   created       no match, and the source may create places in that category
ALTER TABLE place.place_source_link
    ADD COLUMN method     text,
    ADD COLUMN score      real,
    ADD COLUMN linked_run bigint REFERENCES ingest.run (run_id);

-- The Phase 1 seed keyed its places by Overture id, which is also the Overture importer's source key. Handing those
-- links over keeps the places' ids: the first Overture run finds them already linked instead of creating new places.
UPDATE place.place_source_link SET source = 'overture_places', method = 'id' WHERE source = 'overture_seed';

ALTER TABLE place.place_source_link
    ALTER COLUMN method SET NOT NULL,
    ADD CONSTRAINT place_source_link_method CHECK (method IN ('id', 'score', 'corroborated', 'created'));

-- Borderline matches (score 0.60–0.80) wait here for a person; the record stays unlinked meanwhile (ADR 0022).
CREATE TABLE place.match_review (
    source     text        NOT NULL,
    source_key text        NOT NULL,
    place_id   bigint      NOT NULL REFERENCES place.place (place_id),
    score      real        NOT NULL,
    run_id     bigint      NOT NULL REFERENCES ingest.run (run_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, source_key, place_id),
    FOREIGN KEY (source, source_key) REFERENCES place.source_record (source, source_key)
);
