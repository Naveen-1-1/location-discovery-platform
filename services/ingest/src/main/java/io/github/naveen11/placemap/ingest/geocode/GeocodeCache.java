package io.github.naveen11.placemap.ingest.geocode;

import java.util.Collection;
import java.util.Map;

/** Decisions by address query. Never overwritten: the first decision stored for a query is the one every run uses. */
public interface GeocodeCache {

    Map<String, Geocode> lookup(Collection<String> queries);

    /** Stores decisions for queries that have none yet; a query already stored keeps its decision. */
    void store(Map<String, Geocode> decisions);
}
