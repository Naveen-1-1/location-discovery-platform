package io.github.naveen11.placemap.ingest.facts;

import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.source.BostonLicensingBoard;
import io.github.naveen11.placemap.ingest.source.CambridgeFoodPermits;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rebuilds {@code place.place_facts} for the places a completing run touched, inside the run's completion
 * transaction, after the places themselves are refreshed. Facts come from all of a place's live records, whichever
 * source they are from:
 *
 * <ul>
 *   <li><b>Accepts SNAP:</b> a USDA SNAP retailer record is linked.
 *   <li><b>Active licence:</b> a Boston food or Licensing Board licence that is active, or a Cambridge food permit
 *       issued in the last 13 months (they are renewed yearly).
 *   <li><b>Inspection:</b> the latest of the place's inspected records. Boston reports the result ({@code HE_Pass});
 *       Cambridge only the violations found, so its inspection passed if it was completed without any.
 *   <li><b>Hours:</b> the first that reads, in this order: OSM {@code opening_hours} (what the place publishes),
 *       Cambridge's permit hours, Boston's licensed hours (the latest it may stay open, ranked last for that reason).
 * </ul>
 *
 * Places still without a row (every place, the first time) are refreshed too.
 */
public final class PlaceFacts {

    private static final JsonMapper JSON = new JsonMapper();
    private static final List<String> CAMBRIDGE_DAYS = List.of("monday_hours", "tuesday_hours", "wednesday_hours",
            "thursday_hours", "friday_hours", "saturday_hours", "sunday_hours");

    /** Hours sources, best first, as stored in {@code hours_source}. */
    private static final Map<String, String> HOURS_SOURCES = new LinkedHashMap<>();

    static {
        HOURS_SOURCES.put(SourcePriority.OSM, "osm");
        HOURS_SOURCES.put(CambridgeFoodPermits.ID, "cambridge_permit");
        HOURS_SOURCES.put(BostonLicensingBoard.ID, "boston_licence");
    }

    private static final String FACTS = """
            INSERT INTO place.place_facts AS f
                (place_id, accepts_snap, active_licence, inspection, inspected_at, failed_checks_12m, park_features,
                 hours, hours_source, open_week, refreshed_at)
            SELECT t.place_id,
                   coalesce(bool_or(r.source = 'usda_snap_retailers'), false),
                   coalesce(bool_or(r.source = 'boston_active_food_licenses'
                                 OR (r.source = 'boston_licensing_board_licenses' AND r.attrs->>'status' = 'Active')
                                 OR (r.source = 'boston_food_inspections' AND r.attrs->>'licstatus' = 'Active')
                                 OR (r.source = 'cambridge_food_establishment_permits'
                                     AND left(r.attrs->>'issue_date', 10)
                                         >= to_char(now() - interval '13 months', 'YYYY-MM-DD'))), false),
                   latest.inspection, latest.latest_at, latest.failed_checks_12m,
                   (array_agg(r.attrs->>'features' ORDER BY r.source_key)
                        FILTER (WHERE r.source = 'boston_park_features'))[1],
                   NULL, NULL, NULL, now()
            FROM facts_touched t
            LEFT JOIN place.place_source_link l ON l.place_id = t.place_id
            LEFT JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key
                                           AND r.retired_at IS NULL
            LEFT JOIN LATERAL (
                SELECT CASE
                           WHEN s.source = 'boston_food_inspections' THEN
                               CASE WHEN s.latest_result = 'HE_Pass' THEN 'passed'
                                    WHEN s.latest_result LIKE 'HE_Fail%'
                                      OR s.latest_result IN ('HE_Closure', 'HE_Hearing', 'HE_TSOP') THEN 'failed'
                                    ELSE 'other' END
                           WHEN s.latest_failed_checks > 0 THEN 'failed'
                           WHEN s.latest_result = 'Complete' THEN 'passed'
                           ELSE 'other' END AS inspection,
                       s.latest_at, s.failed_checks_12m
                FROM place.place_source_link il
                JOIN place.source_record ir ON ir.source = il.source AND ir.source_key = il.source_key
                                           AND ir.retired_at IS NULL
                JOIN place.inspection_summary s ON s.source = il.source AND s.source_key = il.source_key
                WHERE il.place_id = t.place_id
                ORDER BY s.latest_at DESC, s.source, s.source_key
                LIMIT 1) latest ON true
            GROUP BY t.place_id, latest.inspection, latest.latest_at, latest.failed_checks_12m
            ORDER BY t.place_id
            ON CONFLICT (place_id) DO UPDATE SET
                accepts_snap = EXCLUDED.accepts_snap, active_licence = EXCLUDED.active_licence,
                inspection = EXCLUDED.inspection, inspected_at = EXCLUDED.inspected_at,
                failed_checks_12m = EXCLUDED.failed_checks_12m, park_features = EXCLUDED.park_features,
                hours = NULL, hours_source = NULL, open_week = NULL, refreshed_at = now()
            """;

    private PlaceFacts() {
    }

    /** @return the number of places refreshed */
    public static int refresh(Connection c, String source) throws SQLException {
        var sameConnection = new SingleConnectionDataSource(c, true);
        var tx = JdbcClient.create(sameConnection);
        tx.sql("""
                CREATE TEMP TABLE facts_touched ON COMMIT DROP AS
                SELECT place_id FROM place.place_source_link WHERE source = ?
                UNION
                SELECT p.place_id FROM place.place p
                WHERE p.retired_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM place.place_facts f WHERE f.place_id = p.place_id)
                """).param(source).update();
        int refreshed = tx.sql(FACTS).update();

        var hoursRecords = new LinkedHashMap<Long, Map<String, JsonNode>>(); // place → hours source → its record's attrs
        tx.sql("""
                        SELECT t.place_id, r.source, r.attrs::text AS attrs
                        FROM facts_touched t
                        JOIN place.place_source_link l ON l.place_id = t.place_id
                        JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key
                                                  AND r.retired_at IS NULL
                        WHERE r.source IN (?, ?, ?)
                        ORDER BY t.place_id, r.source, r.source_key
                        """)
                .params(List.copyOf(HOURS_SOURCES.keySet()).toArray())
                .query(rs -> {
                    hoursRecords.computeIfAbsent(rs.getLong("place_id"), k -> new LinkedHashMap<>())
                            .putIfAbsent(rs.getString("source"), JSON.readTree(rs.getString("attrs")));
                });
        var updates = new ArrayList<Object[]>();
        hoursRecords.forEach((place, attrs) -> best(attrs).ifPresent(hours -> updates.add(new Object[] {
                hours.week().describe(), hours.source(), hours.week().multirange(), place})));
        new JdbcTemplate(sameConnection).batchUpdate("""
                UPDATE place.place_facts SET hours = ?, hours_source = ?, open_week = CAST(? AS int4multirange)
                WHERE place_id = ?
                """, updates);
        return refreshed;
    }

    record Hours(WeeklyHours week, String source) {
    }

    /** The best-ranked source whose hours read. */
    static Optional<Hours> best(Map<String, JsonNode> attrsBySource) {
        for (var entry : HOURS_SOURCES.entrySet()) {
            JsonNode attrs = attrsBySource.get(entry.getKey());
            if (attrs == null) {
                continue;
            }
            Optional<WeeklyHours> week = switch (entry.getValue()) {
                case "osm" -> WeeklyHours.fromOsm(text(attrs, "opening_hours"));
                case "cambridge_permit" -> {
                    var days = new EnumMap<DayOfWeek, String>(DayOfWeek.class);
                    for (int d = 0; d < 7; d++) {
                        String written = text(attrs, CAMBRIDGE_DAYS.get(d));
                        if (written != null) {
                            days.put(DayOfWeek.values()[d], written);
                        }
                    }
                    yield WeeklyHours.perDay(days);
                }
                default -> WeeklyHours.daily(text(attrs, "opening"), text(attrs, "closing"));
            };
            if (week.isPresent()) {
                return Optional.of(new Hours(week.get(), entry.getValue()));
            }
        }
        return Optional.empty();
    }

    private static String text(JsonNode attrs, String field) {
        String text = attrs.path(field).asString("");
        return text.isBlank() ? null : text;
    }
}
