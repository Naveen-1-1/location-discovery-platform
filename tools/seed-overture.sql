-- Phase 1 temporary seed: Overture places for the four towns -> data/cache/overture_seed.csv.
-- Follows gate G1 (ADR 0032) for what Overture may create on its own: confidence >= 0.9, not permanently closed, and
-- only categories where Overture is an allowed existence source without corroboration. Replaced by the ingest
-- pipeline in Phase 2.
INSTALL spatial; LOAD spatial;

COPY (
  -- Same lon/lat coordinates, two CRS labels: Overture says OGC:CRS84, the GeoJSON reader says EPSG:4326.
  WITH area AS (SELECT ST_SetCRS(ST_Union_Agg(geom), 'OGC:CRS84') AS g FROM ST_Read('data/area/area.geojson')),
  o AS (
    SELECT *,
           CASE
             WHEN basic_category IN ('restaurant', 'casual_eatery', 'fast_food_restaurant', 'cafe', 'coffee_shop', 'bar') THEN 'food'
             WHEN basic_category = 'convenience_store'
               OR taxonomy.primary IN ('grocery_store', 'asian_grocery_store', 'international_grocery_store',
                                       'organic_grocery_store', 'mexican_grocery_store', 'produce_store') THEN 'grocery'
             WHEN basic_category IN ('gym', 'fitness_studio') THEN 'gym'
             WHEN basic_category = 'park' THEN 'park'
             WHEN basic_category = 'bank_or_credit_union' THEN 'bank'
             WHEN basic_category = 'atm' THEN 'atm'
             WHEN basic_category = 'library' THEN 'library'
             WHEN basic_category = 'community_center' THEN 'community_centre'
           END AS category
    FROM read_parquet('spikes/phase0/work/overture_bbox.parquet')
  )
  SELECT o.id                          AS overture_id,
         o.names.primary               AS name,
         o.category,
         round(ST_X(o.geometry), 7)    AS lon,
         round(ST_Y(o.geometry), 7)    AS lat,
         o.addresses[1].freeform       AS address,
         o.phones[1]                   AS phone,
         o.websites[1]                 AS website
  FROM o, area
  WHERE o.category IS NOT NULL
    AND o.names.primary IS NOT NULL
    AND o.confidence >= 0.9
    AND coalesce(o.operating_status, '') <> 'permanently_closed'
    AND ST_Within(o.geometry, area.g)
  ORDER BY o.id
) TO 'data/cache/overture_seed.csv' (HEADER);

SELECT category, count(*) AS places
FROM read_csv('data/cache/overture_seed.csv', header = true)
GROUP BY ALL ORDER BY places DESC;
