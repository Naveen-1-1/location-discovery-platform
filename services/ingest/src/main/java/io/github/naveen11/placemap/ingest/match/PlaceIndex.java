package io.github.naveen11.placemap.ingest.match;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.distance.DistanceOp;
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
 *   <li>A place whose record has an outline (a park) is also near everything within 100 m of that outline, not just
 *       of its point: a large park's point can be a kilometre from the café at its gate. Such places are also
 *       bucketed under every cell their outline reaches.
 * </ul>
 *
 * Immutable after loading, so match workers share it without locks (JTS's STRtree would take a lock on every
 * query). H3 is native code: load it and query it from platform threads.
 */
public final class PlaceIndex {

    static final int RESOLUTION = 9;

    /** The largest radius {@link #near} serves for outlined places: their outline is bucketed this far out. */
    static final double OUTLINE_REACH_METERS = 100;

    /** A point is at most about one edge (~175 m at resolution 9) from its cell's centre; 200 m covers it. */
    private static final double CELL_MARGIN_METERS = 200;
    private static final double METERS_PER_DEGREE_LAT = 111_320;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    /** A live place as the matcher sees it. {@code outline} is its record's outline, or null. */
    public record IndexedPlace(long placeId, String category, double lon, double lat, String normalizedName,
            Similarity.Address address, Set<String> sources, Geometry outline) {

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
                        FILTER (WHERE r.outline IS NOT NULL AND r.retired_at IS NULL))[1] AS outline
            FROM place.place p
            JOIN place.place_source_link l ON l.place_id = p.place_id
            LEFT JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key
            WHERE p.retired_at IS NULL
              AND p.created_at < (SELECT started_at FROM ingest.run WHERE run_id = ?)
            GROUP BY p.place_id
            """;

    private final H3Core h3;
    private final Map<Long, List<IndexedPlace>> byCell;
    private final Map<Long, List<IndexedPlace>> outlinesByCell;
    private final Map<String, Long> linkedPlace;
    private final int size;

    private PlaceIndex(H3Core h3, List<IndexedPlace> places, Map<String, Long> linkedPlace) {
        this.h3 = h3;
        var points = new HashMap<Long, List<IndexedPlace>>();
        var outlines = new HashMap<Long, List<IndexedPlace>>();
        for (IndexedPlace place : places) {
            points.computeIfAbsent(h3.latLngToCell(place.lat(), place.lon(), RESOLUTION), k -> new ArrayList<>()).add(place);
            if (place.outline() != null) {
                place.outline().getEnvelopeInternal(); // computed lazily by JTS: do it now, before workers share it
                for (long cell : cover(place.outline().getEnvelopeInternal(), OUTLINE_REACH_METERS)) {
                    outlines.computeIfAbsent(cell, k -> new ArrayList<>()).add(place);
                }
            }
        }
        points.replaceAll((cell, list) -> List.copyOf(list));
        outlines.replaceAll((cell, list) -> List.copyOf(list));
        this.byCell = Map.copyOf(points);
        this.outlinesByCell = Map.copyOf(outlines);
        this.linkedPlace = Map.copyOf(linkedPlace);
        this.size = places.size();
    }

    public static PlaceIndex load(DataSource dataSource, String source, long runId) {
        H3Core h3;
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("could not load the H3 native library", e);
        }
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
                            Set.of((String[]) sources.getArray()), outline == null ? null : read(wkt, outline));
                })
                .list();
        var linked = new HashMap<String, Long>();
        jdbc.sql("SELECT source_key, place_id FROM place.place_source_link WHERE source = ?")
                .param(source)
                .query(rs -> {
                    linked.put(rs.getString(1), rs.getLong(2));
                });
        return new PlaceIndex(h3, places, linked);
    }

    /** For tests: an index over the given places and links. */
    static PlaceIndex of(List<IndexedPlace> places, Map<String, Long> linkedPlace) {
        try {
            return new PlaceIndex(H3Core.newInstance(), places, linkedPlace);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /**
     * Live places within {@code radiusMeters} (at most 100 m) of a point, nearest first; ties by place id, so the order
     * is deterministic. An outlined place counts from its outline: 0 m for a point inside it.
     */
    public List<Nearby> near(double lon, double lat, double radiusMeters) {
        if (radiusMeters > OUTLINE_REACH_METERS) {
            throw new IllegalArgumentException("outlines are indexed for radii up to " + OUTLINE_REACH_METERS + " m");
        }
        var result = new LinkedHashMap<Long, Nearby>();
        long origin = h3.latLngToCell(lat, lon, RESOLUTION);
        for (long cell : h3.gridDisk(origin, 1)) {
            for (IndexedPlace place : byCell.getOrDefault(cell, List.of())) {
                double meters = meters(lon, lat, place.lon(), place.lat());
                if (meters <= radiusMeters) {
                    result.put(place.placeId(), new Nearby(place, meters));
                }
            }
        }
        for (IndexedPlace place : outlinesByCell.getOrDefault(origin, List.of())) {
            double meters = metersToOutline(place.outline(), lon, lat);
            if (meters <= radiusMeters) {
                result.merge(place.placeId(), new Nearby(place, meters), (a, b) -> a.meters() <= b.meters() ? a : b);
            }
        }
        return sorted(result.values());
    }

    /**
     * Live places whose point lies within {@code radiusMeters} of an outline (0 m inside it), nearest first: what a
     * record with an outline, such as a park, is compared with.
     */
    public List<Nearby> nearOutline(Geometry outline, double radiusMeters) {
        var result = new ArrayList<Nearby>();
        for (long cell : cover(outline.getEnvelopeInternal(), radiusMeters)) {
            for (IndexedPlace place : byCell.getOrDefault(cell, List.of())) {
                double meters = metersToOutline(outline, place.lon(), place.lat());
                if (meters <= radiusMeters) {
                    result.add(new Nearby(place, meters));
                }
            }
        }
        return sorted(result);
    }

    private static List<Nearby> sorted(Collection<Nearby> nearby) {
        var list = new ArrayList<>(nearby);
        list.sort(Comparator.comparingDouble(Nearby::meters).thenComparingLong(n -> n.place().placeId()));
        return list;
    }

    /** The cells whose points can lie within {@code meters} of the envelope. */
    private List<Long> cover(Envelope envelope, double meters) {
        double dLat = (meters + CELL_MARGIN_METERS) / METERS_PER_DEGREE_LAT;
        double dLon = dLat / Math.cos(Math.toRadians((envelope.getMinY() + envelope.getMaxY()) / 2));
        double south = envelope.getMinY() - dLat;
        double north = envelope.getMaxY() + dLat;
        double west = envelope.getMinX() - dLon;
        double east = envelope.getMaxX() + dLon;
        return h3.polygonToCells(List.of(new LatLng(south, west), new LatLng(south, east), new LatLng(north, east),
                new LatLng(north, west)), List.of(), RESOLUTION);
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
