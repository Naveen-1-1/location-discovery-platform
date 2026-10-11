-- Phase 2, step 4: Overture and OSM imports and record matching (ADR 0022, 0032).

-- Rows that are valid but out of scope (outside the four towns, no app category, permanently closed). They are not
-- stored and do not count toward the 2% reject limit.
ALTER TABLE ingest.batch_done ADD COLUMN skipped int NOT NULL DEFAULT 0;
