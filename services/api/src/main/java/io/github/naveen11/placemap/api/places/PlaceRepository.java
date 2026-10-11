package io.github.naveen11.placemap.api.places;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PlaceRepository {

    // Two steps. The inner query lets the GiST index pick the nearest candidates: "geom <-> origin" stays an
    // index-ordered KNN scan only while the origin is written out in full (not via a CTE), and "retired_at IS NULL"
    // must match the partial index predicate. KNN orders by distance on a sphere, while the distance we show is the
    // accurate spheroid one; the two can disagree by a few metres. The outer query re-sorts those few hundred rows
    // by the displayed distance, so the list can never show 1,122 m above 1,120 m.
    private static final String NEARBY_SQL = """
            SELECT * FROM (
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
            ORDER BY distance_m, place_id
            """;

    private final JdbcClient jdbc;

    public PlaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static String nearbySql(boolean filterByCategory) {
        return NEARBY_SQL.formatted(filterByCategory ? "AND p.category IN (:categories)" : "");
    }

    public List<NearbyPlace> nearby(double lat, double lon, int radiusMeters, List<String> categories, int limit) {
        var spec = jdbc.sql(nearbySql(!categories.isEmpty()))
                .param("lat", lat)
                .param("lon", lon)
                .param("radius", radiusMeters)
                .param("limit", limit);
        if (!categories.isEmpty()) {
            spec = spec.param("categories", categories);
        }
        return spec.query((rs, i) -> new NearbyPlace(
                        rs.getLong("place_id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getString("address"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        Math.round(rs.getDouble("distance_m"))))
                .list();
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT code, label FROM place.category ORDER BY sort_order")
                .query(Category.class)
                .list();
    }
}
