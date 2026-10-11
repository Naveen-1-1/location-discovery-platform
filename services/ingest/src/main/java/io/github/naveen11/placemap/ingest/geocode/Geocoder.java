package io.github.naveen11.placemap.ingest.geocode;

import java.io.IOException;
import java.util.List;

/** A geocoding service: candidates for a free-text address, best first. Called from virtual threads. */
public interface Geocoder {

    List<Candidate> search(String query) throws IOException, InterruptedException;
}
