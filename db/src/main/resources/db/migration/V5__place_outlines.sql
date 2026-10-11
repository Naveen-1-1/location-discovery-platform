-- Phase 5: official place lists. Boston's open-space parcels come with outlines (simplified to about 2 m).
-- Matching compares other records with a park's outline instead of its point, because a large park's point can be a
-- kilometre from its gates; the map can draw the outlines later. Null for every source that publishes points.
ALTER TABLE place.source_record ADD COLUMN outline geography(MultiPolygon, 4326);
