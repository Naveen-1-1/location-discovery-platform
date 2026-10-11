-- Phase 5: the geocoder's decisions, by address query (plan: "Check the cache. Query Photon. Accept only if house
-- number and postcode match and the point is inside the area. Record the quality."). A decision is stored once and
-- never overwritten, so re-runs and resumed runs place every record exactly where the first run did, and each address
-- costs one call to Photon ever. Failed calls are not stored; the next run asks again. Business addresses only:
-- the sources that need geocoding list shops, pharmacies and licensed premises.
CREATE TABLE ingest.geocode_cache (
    query      text        PRIMARY KEY,          -- normalised "<street address>, <town>, ma <zip>"
    lon        double precision,                 -- null when no answer passed the rule
    lat        double precision,
    quality    text        NOT NULL CHECK (quality IN ('house', 'none')),
    detail     text,                             -- the accepted answer's label, or why none was accepted
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((lon IS NULL) = (lat IS NULL) AND (lon IS NULL) = (quality = 'none'))
);
