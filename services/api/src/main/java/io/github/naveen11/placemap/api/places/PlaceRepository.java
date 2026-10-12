package io.github.naveen11.placemap.api.places;

import io.github.naveen11.placemap.api.live.LiveSources;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PlaceRepository {

    // Two steps. The inner query lets the GiST index pick the nearest candidates: "geom <-> origin" stays an
    // index-ordered KNN scan only while the origin is written out in full (not via a CTE), and "retired_at IS NULL"
    // must match the partial index predicate. KNN orders by distance on a sphere, while the distance we show is the
    // accurate spheroid one; the two can disagree by a few metres. The outer query re-sorts those few hundred rows
    // by the displayed distance, so the list can never show 1,122 m above 1,120 m. The live-data link is looked up
    // out there too, for those few hundred rows only, so it cannot get in the way of the index scan. Filters
    // are EXISTS tests on place_facts inside, so the place table stays the scan's only table and the LIMIT counts
    // places that pass them; the facts shown as badges are joined outside.
    private static final String NEARBY_SQL = """
            SELECT nearest.*, live.source AS live_source, live.source_key AS live_source_key,
                   f.accepts_snap, f.active_licence, f.inspection, f.open_week::text AS open_week
            FROM (
                SELECT p.place_id, p.name, p.category, p.address,
                       ST_Y(p.geom::geometry) AS lat, ST_X(p.geom::geometry) AS lon,
                       ST_Distance(p.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) AS distance_m
                FROM place.place p
                WHERE p.retired_at IS NULL
                  AND ST_DWithin(p.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius)
                  %s
                ORDER BY p.geom <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography
                LIMIT :limit
            ) nearest
            LEFT JOIN LATERAL (
                SELECT l.source, l.source_key FROM place.place_source_link l
                WHERE l.place_id = nearest.place_id AND l.source IN (:liveSources)
                ORDER BY l.source, l.source_key
                LIMIT 1
            ) live ON true
            LEFT JOIN place.place_facts f ON f.place_id = nearest.place_id
            ORDER BY distance_m, place_id
            """;

    private final JdbcClient jdbc;

    public PlaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static String nearbySql(boolean filterByCategory) {
        return nearbySql(filterByCategory, PlaceFilters.NONE);
    }

    static String nearbySql(boolean filterByCategory, PlaceFilters filters) {
        return NEARBY_SQL.formatted(nearbyFilters(filterByCategory, filters));
    }

    /** The WHERE conditions for categories and filters, on {@code place.place p}; fixed fragments only. */
    private static String nearbyFilters(boolean filterByCategory, PlaceFilters filters) {
        var where = new StringBuilder(filterByCategory ? "AND p.category IN (:categories)" : "");
        if (filters.any()) {
            var facts = new ArrayList<String>();
            if (filters.acceptsSnap()) {
                facts.add("f.accepts_snap");
            }
            if (filters.licensed()) {
                facts.add("f.active_licence");
            }
            if (filters.passedInspection()) {
                facts.add("f.inspection = 'passed'");
            }
            if (filters.openAt() != null) {
                facts.add("f.open_week @> CAST(:openAt AS int)"); // unknown hours: null, so never open
            }
            where.append("\n  AND EXISTS (SELECT 1 FROM place.place_facts f WHERE f.place_id = p.place_id AND ")
                    .append(String.join(" AND ", facts)).append(")");
        }
        return where.toString();
    }

    public List<NearbyPlace> nearby(double lat, double lon, int radiusMeters, List<String> categories, int limit) {
        return nearby(lat, lon, radiusMeters, categories, PlaceFilters.NONE, limit);
    }

    public List<NearbyPlace> nearby(double lat, double lon, int radiusMeters, List<String> categories,
            PlaceFilters filters, int limit) {
        var spec = jdbc.sql(nearbySql(!categories.isEmpty(), filters))
                .param("lat", lat)
                .param("lon", lon)
                .param("radius", radiusMeters)
                .param("limit", limit)
                .param("liveSources", List.copyOf(LiveSources.TOPICS.keySet()));
        if (!categories.isEmpty()) {
            spec = spec.param("categories", categories);
        }
        if (filters.openAt() != null) {
            spec = spec.param("openAt", filters.openAt());
        }
        return spec.query((rs, i) -> new NearbyPlace(
                        rs.getLong("place_id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getString("address"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        Math.round(rs.getDouble("distance_m")),
                        null,
                        null,
                        LiveSources.key(rs.getString("live_source"), rs.getString("live_source_key")).orElse(null),
                        null,
                        facts(rs)))
                .list();
    }

    /**
     * With "open now" on: places within the radius that pass the other filters but whose hours are unknown, so the
     * list can say how many it left out.
     */
    public int unknownHours(double lat, double lon, int radiusMeters, List<String> categories, PlaceFilters filters) {
        var others = new PlaceFilters(filters.acceptsSnap(), filters.licensed(), filters.passedInspection(), null);
        String where = nearbyFilters(!categories.isEmpty(), others)
                + "\n  AND NOT EXISTS (SELECT 1 FROM place.place_facts h WHERE h.place_id = p.place_id"
                + " AND h.open_week IS NOT NULL)";
        var spec = jdbc.sql("""
                        SELECT count(*) FROM place.place p
                        WHERE p.retired_at IS NULL
                          AND ST_DWithin(p.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius)
                          %s
                        """.formatted(where))
                .param("lat", lat)
                .param("lon", lon)
                .param("radius", radiusMeters);
        if (!categories.isEmpty()) {
            spec = spec.param("categories", categories);
        }
        return spec.query(Integer.class).single();
    }

    private static PlaceFacts facts(ResultSet rs) throws SQLException {
        boolean snap = rs.getBoolean("accepts_snap");
        if (rs.wasNull()) {
            return PlaceFacts.NONE; // not refreshed since the place appeared
        }
        return new PlaceFacts(snap, rs.getBoolean("active_licence"), rs.getString("inspection"), null,
                OpenWeek.parse(rs.getString("open_week")));
    }

    /** A live place's position; empty if there is no such place or it has been retired. */
    public Optional<PlaceLocation> location(long placeId) {
        return jdbc.sql("""
                        SELECT name, ST_Y(geom::geometry) AS lat, ST_X(geom::geometry) AS lon
                        FROM place.place WHERE place_id = :id AND retired_at IS NULL
                        """)
                .param("id", placeId)
                .query((rs, i) -> new PlaceLocation(placeId, rs.getString("name"), rs.getDouble("lat"),
                        rs.getDouble("lon")))
                .optional();
    }

    private static final String DETAILS_SQL = """
            SELECT p.place_id, p.name, p.category, p.address, p.phone, p.website,
                   ST_Y(p.geom::geometry) AS lat, ST_X(p.geom::geometry) AS lon,
                   f.accepts_snap, f.active_licence, f.inspection, f.inspected_at, f.failed_checks_12m,
                   f.park_features, f.hours, f.hours_source, f.open_week::text AS open_week,
                   (SELECT array_agg(DISTINCT l.source ORDER BY l.source)
                    FROM place.place_source_link l
                    JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key
                                              AND r.retired_at IS NULL
                    WHERE l.place_id = p.place_id) AS sources,
                   (SELECT l.source || '|' || l.source_key FROM place.place_source_link l
                    WHERE l.place_id = p.place_id AND l.source IN (:liveSources)
                    ORDER BY l.source, l.source_key LIMIT 1) AS live_link
            FROM place.place p
            LEFT JOIN place.place_facts f ON f.place_id = p.place_id
            WHERE p.place_id = :id AND p.retired_at IS NULL
            """;

    /** A live place's details, with "open now" judged at {@code minuteOfWeek}; empty if there is none. */
    public Optional<PlaceDetails> details(long placeId, int minuteOfWeek) {
        return jdbc.sql(DETAILS_SQL)
                .param("id", placeId)
                .param("liveSources", List.copyOf(LiveSources.TOPICS.keySet()))
                .query((rs, i) -> {
                    String live = rs.getString("live_link");
                    String liveKey = live == null ? null
                            : LiveSources.key(live.substring(0, live.indexOf('|')), live.substring(live.indexOf('|') + 1))
                                    .orElse(null);
                    Array sources = rs.getArray("sources");
                    return new PlaceDetails(rs.getLong("place_id"), rs.getString("name"), rs.getString("category"),
                            rs.getString("address"), rs.getString("phone"), rs.getString("website"), rs.getDouble("lat"),
                            rs.getDouble("lon"), liveKey, detailFacts(rs, minuteOfWeek),
                            sources == null ? List.of() : List.of((String[]) sources.getArray()));
                })
                .optional();
    }

    private static PlaceDetails.Facts detailFacts(ResultSet rs, int minuteOfWeek) throws SQLException {
        boolean snap = rs.getBoolean("accepts_snap");
        if (rs.wasNull()) {
            return new PlaceDetails.Facts(false, false, null, null, List.of());
        }
        String result = rs.getString("inspection");
        PlaceDetails.Inspection inspection = result == null ? null : new PlaceDetails.Inspection(result,
                rs.getObject("inspected_at", OffsetDateTime.class), (Integer) rs.getObject("failed_checks_12m"));
        OpenWeek week = OpenWeek.parse(rs.getString("open_week"));
        PlaceDetails.Hours hours = rs.getString("hours") == null || week == null ? null
                : new PlaceDetails.Hours(rs.getString("hours"), rs.getString("hours_source"), week.openAt(minuteOfWeek));
        return new PlaceDetails.Facts(snap, rs.getBoolean("active_licence"), inspection, hours,
                features(rs.getString("park_features")));
    }

    /** "Basketball:2;Playground:1" → the features, in the stored (alphabetical) order. */
    static List<PlaceDetails.Feature> features(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        var features = new ArrayList<PlaceDetails.Feature>();
        for (String item : stored.split(";")) {
            int colon = item.lastIndexOf(':');
            if (colon > 0) {
                features.add(new PlaceDetails.Feature(item.substring(0, colon), Integer.parseInt(item.substring(colon + 1))));
            }
        }
        return List.copyOf(features);
    }

    /** A place a name search found. */
    public record NameMatch(long id, String name, String category, String address, double lat, double lon,
            long distanceMeters) {
    }

    // "query <% name": the query matches some part of the name (pg_trgm word similarity, at least 0.6 by default),
    // through the trigram index of migration V9. Best match first, nearer first among equals.
    private static final String NAME_SEARCH_SQL = """
            SELECT p.place_id, p.name, p.category, p.address,
                   ST_Y(p.geom::geometry) AS lat, ST_X(p.geom::geometry) AS lon,
                   ST_Distance(p.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) AS distance_m
            FROM place.place p
            WHERE p.retired_at IS NULL AND place.search_text(:q) <% place.search_text(p.name)
            ORDER BY word_similarity(place.search_text(:q), place.search_text(p.name)) DESC, distance_m, p.place_id
            LIMIT :limit
            """;

    /** Live places whose name matches the typed text, accents and case aside. */
    public List<NameMatch> searchNames(String query, double lat, double lon, int limit) {
        return jdbc.sql(NAME_SEARCH_SQL)
                .param("q", query)
                .param("lat", lat)
                .param("lon", lon)
                .param("limit", limit)
                .query((rs, i) -> new NameMatch(rs.getLong("place_id"), rs.getString("name"), rs.getString("category"),
                        rs.getString("address"), rs.getDouble("lat"), rs.getDouble("lon"),
                        Math.round(rs.getDouble("distance_m"))))
                .list();
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT code, label FROM place.category ORDER BY sort_order")
                .query(Category.class)
                .list();
    }
}
