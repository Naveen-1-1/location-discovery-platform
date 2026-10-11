-- Phase 5: place-name search (ADR 0008), typo-tolerant and accent-insensitive: "cafe" finds "Café Vanille".
-- unaccent() is only STABLE (its dictionary could change), so it cannot be used in an index; naming the dictionary
-- and declaring the wrapper IMMUTABLE is the documented way to index unaccented text.
CREATE FUNCTION place.search_text(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN lower(public.unaccent('public.unaccent'::regdictionary, $1));

-- Serves "<query> <% search_text(name)" (word similarity: the query matches a part of the name, as when typing).
CREATE INDEX place_name_search ON place.place USING gin (place.search_text(name) gin_trgm_ops)
    WHERE retired_at IS NULL;
