package io.github.naveen11.placemap.ingest.match;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.index.hprtree.HPRtree;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The places a run matches against, loaded once before the match workers start and never changed afterwards.
 * Because every record is compared with the same frozen index, matching is safe to run on many threads
 * and gives the same answer whatever order batches take.
 *
 * <ul>
 *   <li>Only places that existed when the run <em>started</em> are included. A resumed run therefore sees exactly
 *       what the first attempt saw, not the places its earlier batches created, so a crash cannot change the
 *       result (crash test C1).
 *   <li>Places sit in a JTS {@link HPRtree} by their point. A search asks it for everything in a box around the point
 *       a little larger than the radius, then keeps what is within the radius by great-circle distance.
 *   <li>The run's own source's existing links are included too: a record already linked keeps its place, which is
 *       what keeps {@code place_id}s stable.
 *   <li>A place whose record has an outline (a park) is also near everything within 100 m of that outline, not just
 *       of its point: a large park's point can be a kilometre from the café at its gate. Such places sit in a second
 *       tree by their outline's extent.
 * </ul>
 *
 * Immutable after loading: both trees are built before the index is shared, after which an HPRtree reads without
 * locks, so match workers share it freely.
 */
public final class PlaceIndex {

    /** The largest radius {@link #near} serves. */
    static final double OUTLINE_REACH_METERS = 100;

    /** Fewer metres than any degree of latitude spans, so a box of {@code r / this} degrees always holds radius r. */
    private static final double METERS_PER_DEGREE_AT_MOST = 110_000;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    /**
     * A live place as the matcher sees it. {@code outline} is its record's outline, or null. {@code anchor} names it
     * independently of its id, which depends on the order batches committed in: the smallest "source:key" among the
     * records linked to it (as the import's checksum names places).
     */
    public record IndexedPlace(long placeId, String category, double lon, double lat, String normalizedName,
            Similarity.Address address, Set<String> sources, Geometry outline, String anchor) {

        /** A place named by its id, as in the tests and the matching sample, where ids do not vary. */
        public IndexedPlace(long placeId, String category, double lon, double lat, String normalizedName,
                Similarity.Address address, Set<String> sources, Geometry outline) {
            this(placeId, category, lon, lat, normalizedName, address, sources, outline, "%019d".formatted(placeId));
        }

        public IndexedPlace(long placeId, String category, double lon, double lat, String normalizedName,
                Similarity.Address address, Set<String> sources) {
            this(placeId, category, lon, lat, normalizedName, address, sources, null);
        }
    }

    /** A place near a point, with its distance. */
    public record Nearby(IndexedPlace place, double meters) {
    }

    private static final String PLACES = """
            SELECT p.place_id, p.name, p.category, ST_X(p.geom::geometry) AS lon, ST_Y(p.geom::geometry) AS lat,
                   p.address,
                   coalesce(array_agg(DISTINCT l.source) FILTER (WHERE r.retired_at IS NULL), '{}') AS sources,
                   (array_agg(ST_AsText(r.outline::geometry) ORDER BY l.source, l.source_key)
                        FILTER (WHERE r.outline IS NOT NULL AND r.retired_at IS NULL))[1] AS outline,
                   min((l.source || ':' || l.source_key) COLLATE "C") AS anchor
            FROM place.place p
            JOIN place.place_source_link l ON l.place_id = p.place_id
            LEFT JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key
            WHERE p.retired_at IS NULL
              AND p.created_at < (SELECT started_at FROM ingest.run WHERE run_id = ?)
            GROUP BY p.place_id
            """;

    private final HPRtree points = new HPRtree();
    private final HPRtree outlines = new HPRtree();
    private final Map<String, Long> linkedPlace;
    private final String joinSource;
    private final Map<String, Long> joinedPlace;
    private final int size;

    private PlaceIndex(List<IndexedPlace> places, Map<String, Long> linkedPlace, String joinSource,
            Map<String, Long> joinedPlace) {
        this.joinSource = joinSource;
        this.joinedPlace = Map.copyOf(joinedPlace);
        for (IndexedPlace place : places) {
            points.insert(new Envelope(place.lon(), place.lon(), place.lat(), place.lat()), place);
            if (place.outline() != null) {
                // JTS computes an envelope lazily: do it now, before match workers share the outline.
                outlines.insert(place.outline().getEnvelopeInternal(), place);
            }
        }
        points.build(); // built once here; queries then read without locks
        outlines.build();
        this.linkedPlace = Map.copyOf(linkedPlace);
        this.size = places.size();
    }

    /** @param joinSource the source this one joins by key ({@code SourceAdapter.joinsSource()}), or null */
    public static PlaceIndex load(DataSource dataSource, String source, long runId, String joinSource) {
        var jdbc = JdbcClient.create(dataSource);
        var wkt = new WKTReader();
        List<IndexedPlace> places = jdbc.sql(PLACES)
                .param(runId)
                .query((rs, i) -> {
                    Array sources = rs.getArray("sources");
                    String outline = rs.getString("outline");
                    return new IndexedPlace(rs.getLong("place_id"), rs.getString("category"), rs.getDouble("lon"),
                            rs.getDouble("lat"), Similarity.normalizeName(rs.getString("name")),
                            Similarity.normalizeAddress(rs.getString("address")),
                            Set.of((String[]) sources.getArray()), outline == null ? null : read(wkt, outline),
                            rs.getString("anchor"));
                })
                .list();
        var linked = new HashMap<String, Long>();
        jdbc.sql("SELECT source_key, place_id FROM place.place_source_link WHERE source = ?")
                .param(source)
                .query(rs -> {
                    linked.put(rs.getString(1), rs.getLong(2));
                });
        var joined = new HashMap<String, Long>();
        if (joinSource != null) {
            // The same frozen view as the places: live places that existed when the run started.
            jdbc.sql("""
                            SELECT l.source_key, l.place_id FROM place.place_source_link l
                            JOIN place.place p ON p.place_id = l.place_id
                            WHERE l.source = ? AND p.retired_at IS NULL
                              AND p.created_at < (SELECT started_at FROM ingest.run WHERE run_id = ?)
                            """)
                    .params(joinSource, runId)
                    .query(rs -> {
                        joined.put(rs.getString(1), rs.getLong(2));
                    });
        }
        return new PlaceIndex(places, linked, joinSource, joined);
    }

    /** For tests: an index over the given places and links. */
    static PlaceIndex of(List<IndexedPlace> places, Map<String, Long> linkedPlace) {
        return of(places, linkedPlace, null, Map.of());
    }

    /** For tests: an index for a source that joins {@code joinSource}'s places by key. */
    static PlaceIndex of(List<IndexedPlace> places, Map<String, Long> linkedPlace, String joinSource,
            Map<String, Long> joinedPlace) {
        return new PlaceIndex(places, linkedPlace, joinSource, joinedPlace);
    }

    /** Parses WGS84 WKT, such as an outline written by the importer. */
    public static Geometry read(WKTReader reader, String wkt) {
        try {
            return reader.read(wkt);
        } catch (ParseException e) {
            throw new IllegalArgumentException("unreadable outline: " + e.getMessage(), e);
        }
    }

    /** The place this source's record is already linked to, or null. */
    public Long linkedPlace(String sourceKey) {
        return linkedPlace.get(sourceKey);
    }

    /** The source this run's records join by key, or null if they are matched by score. */
    public String joinSource() {
        return joinSource;
    }

    /** The place of the {@link #joinSource()} record with this key, or null. */
    public Long joinedPlace(String sourceKey) {
        return joinedPlace.get(sourceKey);
    }

    /**
     * Live places within {@code radiusMeters} (at most 100 m) of a point, nearest first; ties by the places' anchors,
     * so the order does not depend on the order earlier batches committed in. An outlined place counts from its
     * outline: 0 m for a point inside it.
     */
    public List<Nearby> near(double lon, double lat, double radiusMeters) {
        if (radiusMeters > OUTLINE_REACH_METERS) {
            throw new IllegalArgumentException("searches reach at most " + OUTLINE_REACH_METERS + " m");
        }
        var result = new LinkedHashMap<Long, Nearby>();
        Envelope around = around(new Envelope(lon, lon, lat, lat), radiusMeters);
        points.query(around, item -> {
            var place = (IndexedPlace) item;
            double meters = meters(lon, lat, place.lon(), place.lat());
            if (meters <= radiusMeters) {
                result.put(place.placeId(), new Nearby(place, meters));
            }
        });
        outlines.query(around, item -> {
            var place = (IndexedPlace) item;
            double meters = metersToOutline(place.outline(), lon, lat);
            if (meters <= radiusMeters) {
                result.merge(place.placeId(), new Nearby(place, meters), (a, b) -> a.meters() <= b.meters() ? a : b);
            }
        });
        return sorted(result.values());
    }

    /**
     * Live places whose point lies within {@code radiusMeters} of an outline (0 m inside it), nearest first: what a
     * record with an outline, such as a park, is compared with.
     */
    public List<Nearby> nearOutline(Geometry outline, double radiusMeters) {
        var result = new ArrayList<Nearby>();
        points.query(around(outline.getEnvelopeInternal(), radiusMeters), item -> {
            var place = (IndexedPlace) item;
            double meters = metersToOutline(outline, place.lon(), place.lat());
            if (meters <= radiusMeters) {
                result.add(new Nearby(place, meters));
            }
        });
        return sorted(result);
    }

    private static List<Nearby> sorted(Collection<Nearby> nearby) {
        var list = new ArrayList<>(nearby);
        list.sort(Comparator.comparingDouble(Nearby::meters).thenComparing(n -> n.place().anchor()));
        return list;
    }

    /** A box holding everything within {@code meters} of the envelope, and a little more. */
    private static Envelope around(Envelope envelope, double meters) {
        double dLat = meters / METERS_PER_DEGREE_AT_MOST;
        double widestLat = Math.max(Math.abs(envelope.getMinY()), Math.abs(envelope.getMaxY())) + dLat;
        double dLon = dLat / Math.cos(Math.toRadians(widestLat));
        var box = new Envelope(envelope);
        box.expandBy(dLon, dLat);
        return box;
    }

    /** 0 inside the outline, else the great-circle distance to its nearest edge. */
    static double metersToOutline(Geometry outline, double lon, double lat) {
        var point = GEOMETRY.createPoint(new Coordinate(lon, lat));
        if (outline.covers(point)) {
            return 0;
        }
        Coordinate nearest = DistanceOp.nearestPoints(outline, point)[0];
        return meters(lon, lat, nearest.x, nearest.y);
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
