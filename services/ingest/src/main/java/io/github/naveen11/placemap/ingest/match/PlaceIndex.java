package io.github.naveen11.placemap.ingest.match;

import com.uber.h3core.H3Core;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The places a run matches against, loaded once before the match workers start and never changed afterwards
 * (ADR 0022). Because every record is compared with the same frozen index, matching is safe to run on many threads
 * and gives the same answer whatever order batches take.
 *
 * <ul>
 *   <li>Only places that existed when the run <em>started</em> are included. A resumed run therefore sees exactly
 *       what the first attempt saw, not the places its earlier batches created, so a crash cannot change the
 *       result (crash test C1).
 *   <li>Places are bucketed by H3 cell at resolution 9 (cells ~0.1 km²). Anything within 100 m of a point lies in
 *       its cell or the six around it.
 *   <li>The run's own source's existing links are included too: a record already linked keeps its place, which is
 *       what keeps {@code place_id}s stable.
 * </ul>
 *
 * Immutable after loading, so match workers share it. H3 is native code: load it and query it from platform threads.
 */
public final class PlaceIndex {

    static final int RESOLUTION = 9;

    /** A live place as the matcher sees it. */
    public record IndexedPlace(long placeId, String category, double lon, double lat, String normalizedName,
            Similarity.Address address, Set<String> sources) {
    }

    /** A place near a point, with its distance. */
    public record Nearby(IndexedPlace place, double meters) {
    }

    private static final String PLACES = """
            SELECT p.place_id, p.name, p.category, ST_X(p.geom::geometry) AS lon, ST_Y(p.geom::geometry) AS lat,
                   p.address,
                   coalesce(array_agg(DISTINCT l.source) FILTER (WHERE r.retired_at IS NULL), '{}') AS sources
            FROM place.place p
            JOIN place.place_source_link l ON l.place_id = p.place_id
            LEFT JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key
            WHERE p.retired_at IS NULL
              AND p.created_at < (SELECT started_at FROM ingest.run WHERE run_id = ?)
            GROUP BY p.place_id
            """;

    private final H3Core h3;
    private final Map<Long, List<IndexedPlace>> byCell;
    private final Map<String, Long> linkedPlace;
    private final int size;

    private PlaceIndex(H3Core h3, Map<Long, List<IndexedPlace>> byCell, Map<String, Long> linkedPlace, int size) {
        this.h3 = h3;
        this.byCell = byCell;
        this.linkedPlace = linkedPlace;
        this.size = size;
    }

    public static PlaceIndex load(DataSource dataSource, String source, long runId) {
        H3Core h3;
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("could not load the H3 native library", e);
        }
        var jdbc = JdbcClient.create(dataSource);
        List<IndexedPlace> places = jdbc.sql(PLACES)
                .param(runId)
                .query((rs, i) -> {
                    Array sources = rs.getArray("sources");
                    return new IndexedPlace(rs.getLong("place_id"), rs.getString("category"), rs.getDouble("lon"),
                            rs.getDouble("lat"), Similarity.normalizeName(rs.getString("name")),
                            Similarity.normalizeAddress(rs.getString("address")),
                            Set.of((String[]) sources.getArray()));
                })
                .list();
        var byCell = new HashMap<Long, List<IndexedPlace>>();
        for (IndexedPlace place : places) {
            byCell.computeIfAbsent(h3.latLngToCell(place.lat(), place.lon(), RESOLUTION), k -> new ArrayList<>()).add(place);
        }
        byCell.replaceAll((cell, list) -> List.copyOf(list));
        var linked = new HashMap<String, Long>();
        jdbc.sql("SELECT source_key, place_id FROM place.place_source_link WHERE source = ?")
                .param(source)
                .query(rs -> {
                    linked.put(rs.getString(1), rs.getLong(2));
                });
        return new PlaceIndex(h3, Map.copyOf(byCell), Map.copyOf(linked), places.size());
    }

    /** For tests: an index over the given places, with no existing links. */
    static PlaceIndex of(List<IndexedPlace> places, Map<String, Long> linkedPlace) {
        try {
            H3Core h3 = H3Core.newInstance();
            var byCell = new HashMap<Long, List<IndexedPlace>>();
            for (IndexedPlace place : places) {
                byCell.computeIfAbsent(h3.latLngToCell(place.lat(), place.lon(), RESOLUTION), k -> new ArrayList<>())
                        .add(place);
            }
            return new PlaceIndex(h3, byCell, linkedPlace, places.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The place this source's record is already linked to, or null. */
    public Long linkedPlace(String sourceKey) {
        return linkedPlace.get(sourceKey);
    }

    /** Live places within {@code radiusMeters}, nearest first (ties by place id, so the order is deterministic). */
    public List<Nearby> near(double lon, double lat, double radiusMeters) {
        var result = new ArrayList<Nearby>();
        for (long cell : h3.gridDisk(h3.latLngToCell(lat, lon, RESOLUTION), 1)) {
            for (IndexedPlace place : byCell.getOrDefault(cell, List.of())) {
                double meters = meters(lon, lat, place.lon(), place.lat());
                if (meters <= radiusMeters) {
                    result.add(new Nearby(place, meters));
                }
            }
        }
        result.sort(Comparator.comparingDouble(Nearby::meters).thenComparingLong(n -> n.place().placeId()));
        return result;
    }

    public int size() {
        return size;
    }

    /** Great-circle distance; at 100 m the spherical error is centimetres. */
    static double meters(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6_371_008.8 * Math.asin(Math.sqrt(a));
    }
}
