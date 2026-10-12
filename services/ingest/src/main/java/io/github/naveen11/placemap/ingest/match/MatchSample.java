package io.github.naveen11.placemap.ingest.match;

import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Draws the matching evaluation's sample.
 *
 * <p>Every live, located record of each evaluated source is scored, with the matcher's own code, against the records
 * of <em>other</em> sources within 100 m that belong to a live place, best record per place. That is what the matcher
 * compares it with, since a place shows its best-ranked record's name and address; but never against the record itself
 * (a place it created would match it trivially). Its best three candidate places and its band by best score are kept:
 * {@code link} (0.80 and up, the threshold when the sample was designed), {@code near} (0.70–0.80), {@code review} (0.60–0.70) and {@code low} (below
 * 0.60). A record with no candidate at all has nothing to judge and is only counted. The sample takes the same number
 * of cases from each band, so the rare high and borderline scores are well represented; the evaluation weights each
 * band back by its real size ({@code bands} in the meta file).
 *
 * <p>A fixed seed makes the sample reproducible; 100 cases are marked {@code blind} for the owner's independent check.
 * Display-only sources (MBTA, Bluebikes) and sources nothing else describes (hospitals, Wi-Fi) are not evaluated;
 * parks are matched by outline and are left for a later round.
 */
public final class MatchSample {

    public static final List<String> SOURCES = List.of("overture_places", "boston_active_food_licenses",
            "boston_licensing_board_licenses", "cambridge_food_establishment_permits", "usda_snap_retailers",
            "nppes_npi_pharmacies", "fdic_bank_locations", "boston_public_libraries", "boston_bcyf_community_centers");
    static final List<String> BANDS = List.of("link", "near", "review", "low");
    static final int CANDIDATES = 3;
    /** The link band's floor: the threshold when the bands were drawn, kept so later samples compare with the first. */
    static final double LINK_BAND = 0.80;
    static final int BLIND = 100;

    record Candidate(long placeId, String name, String address, String category, double meters, double score) {
    }

    record Case(String source, SourceRow record, List<Candidate> candidates) {

        String band() {
            double best = candidates.getFirst().score();
            return best >= LINK_BAND ? "link" : best >= 0.70 ? "near" : best >= Matcher.REVIEW_THRESHOLD
                    ? "review" : "low";
        }
    }

    /** A live record that belongs to a live place. */
    private record Member(long placeId, String source, String name, String address, String category, double lon,
            double lat) {
    }

    private MatchSample() {
    }

    /** Writes {@code sample} (CSV) and its meta file next to it ({@code -meta.json}). */
    public static void export(DataSource dataSource, SourcePriority priority, int perBand, long seed, Path sample)
            throws IOException {
        var jdbc = JdbcClient.create(dataSource);
        List<Member> members = jdbc.sql("""
                        SELECT l.place_id, r.source, r.name, r.address, r.category,
                               ST_X(r.geom::geometry) AS lon, ST_Y(r.geom::geometry) AS lat
                        FROM place.source_record r
                        JOIN place.place_source_link l ON l.source = r.source AND l.source_key = r.source_key
                        JOIN place.place p ON p.place_id = l.place_id AND p.retired_at IS NULL
                        WHERE r.retired_at IS NULL AND r.geom IS NOT NULL
                        ORDER BY l.place_id, r.source, r.source_key COLLATE "C"
                        """)
                .query((rs, i) -> new Member(rs.getLong("place_id"), rs.getString("source"), rs.getString("name"),
                        rs.getString("address"), rs.getString("category"), rs.getDouble("lon"), rs.getDouble("lat")))
                .list();

        var cases = new ArrayList<Case>();
        var population = new TreeMap<String, Map<String, Integer>>(); // source → band → records
        for (String source : SOURCES) {
            // Index entries are the other sources' records, numbered by their position in members.
            var entries = new ArrayList<PlaceIndex.IndexedPlace>();
            for (int i = 0; i < members.size(); i++) {
                Member m = members.get(i);
                if (!m.source().equals(source)) {
                    entries.add(new PlaceIndex.IndexedPlace(i, m.category(), m.lon(), m.lat(),
                            Similarity.normalizeName(m.name()), Similarity.normalizeAddress(m.address()),
                            Set.of(m.source())));
                }
            }
            PlaceIndex index = PlaceIndex.of(entries, Map.of());
            Map<String, Integer> bands = population.computeIfAbsent(source, s -> new TreeMap<>());
            for (SourceRow record : records(jdbc, source)) {
                String name = Similarity.normalizeName(record.name());
                Similarity.Address address = Similarity.normalizeAddress(record.address());
                var bestPerPlace = new LinkedHashMap<Long, Candidate>();
                for (PlaceIndex.Nearby near : index.near(record.lon(), record.lat(), Matcher.RADIUS_METERS)) {
                    if (!priority.compatible(record.category(), near.place().category())) {
                        continue; // as the matcher: never a different kind of place
                    }
                    Member m = members.get((int) near.place().placeId());
                    var candidate = new Candidate(m.placeId(), m.name(), m.address(), m.category(),
                            Math.round(near.meters() * 10) / 10.0, round(Matcher.score(record, name, address, near)));
                    bestPerPlace.merge(m.placeId(), candidate, (a, b) -> a.score() >= b.score() ? a : b);
                }
                if (bestPerPlace.isEmpty()) {
                    bands.merge("none", 1, Integer::sum);
                    continue;
                }
                List<Candidate> scored = new ArrayList<>(bestPerPlace.values());
                scored.sort((a, b) -> a.score() != b.score() ? Double.compare(b.score(), a.score())
                        : Long.compare(a.placeId(), b.placeId()));
                var c = new Case(source, record, List.copyOf(scored.subList(0, Math.min(CANDIDATES, scored.size()))));
                bands.merge(c.band(), 1, Integer::sum);
                cases.add(c);
            }
        }

        var random = new Random(seed);
        var chosen = new ArrayList<Case>();
        for (String band : BANDS) {
            List<Case> inBand = new ArrayList<>(cases.stream().filter(c -> c.band().equals(band)).toList());
            Collections.shuffle(inBand, random);
            chosen.addAll(inBand.subList(0, Math.min(perBand, inBand.size())));
        }
        var order = new ArrayList<Integer>();
        for (int i = 0; i < chosen.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);
        Set<Integer> blind = new HashSet<>(order.subList(0, Math.min(BLIND, order.size())));
        write(chosen, blind, sample);

        var bandTotals = new TreeMap<String, Integer>();
        population.values().forEach(m -> m.forEach((band, n) -> bandTotals.merge(band, n, Integer::sum)));
        var sampled = new TreeMap<String, Integer>();
        chosen.forEach(c -> sampled.merge(c.band(), 1, Integer::sum));
        var meta = new LinkedHashMap<String, Object>();
        meta.put("seed", seed);
        meta.put("radius_m", Matcher.RADIUS_METERS);
        meta.put("bands", bandTotals);
        meta.put("sampled", sampled);
        meta.put("by_source", population);
        Files.writeString(metaPath(sample), new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(meta));
    }

    static Path metaPath(Path sample) {
        return sample.resolveSibling(sample.getFileName().toString().replaceFirst("\\.csv$", "") + "-meta.json");
    }

    private static List<SourceRow> records(JdbcClient jdbc, String source) {
        return jdbc.sql("""
                        SELECT source_key, name, category, ST_X(geom::geometry) AS lon, ST_Y(geom::geometry) AS lat,
                               address, postcode
                        FROM place.source_record
                        WHERE source = ? AND retired_at IS NULL AND geom IS NOT NULL
                        ORDER BY source_key COLLATE "C"
                        """)
                .param(source)
                .query((rs, i) -> SourceRow.of(rs.getString("source_key"), rs.getString("name"), rs.getString("category"),
                        rs.getDouble("lon"), rs.getDouble("lat"), rs.getString("address"), rs.getString("postcode"),
                        null, null, Map.of()))
                .list();
    }

    static final List<String> HEADER = header();

    private static List<String> header() {
        var header = new ArrayList<>(List.of("case_id", "blind", "source", "source_key", "record_name", "record_category",
                "record_address", "band", "best_score"));
        for (int k = 1; k <= CANDIDATES; k++) {
            for (String field : List.of("place_id", "name", "address", "category", "meters", "score")) {
                header.add("cand" + k + "_" + field);
            }
        }
        header.addAll(List.of("label", "label_reason"));
        return List.copyOf(header);
    }

    private static void write(List<Case> cases, Set<Integer> blind, Path sample) throws IOException {
        Files.createDirectories(sample.toAbsolutePath().getParent());
        try (Writer out = Files.newBufferedWriter(sample, StandardCharsets.UTF_8);
                var csv = new CSVPrinter(out, CSVFormat.RFC4180.builder().setHeader(HEADER.toArray(String[]::new)).get())) {
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                var row = new ArrayList<String>(HEADER.size());
                row.addAll(List.of(String.format(Locale.ROOT, "m%03d", i + 1), blind.contains(i) ? "1" : "0", c.source(),
                        c.record().key(), c.record().name(), nullToEmpty(c.record().category()),
                        nullToEmpty(c.record().address()), c.band(), Double.toString(c.candidates().getFirst().score())));
                for (int k = 0; k < CANDIDATES; k++) {
                    if (k < c.candidates().size()) {
                        Candidate x = c.candidates().get(k);
                        row.addAll(List.of(Long.toString(x.placeId()), x.name(), nullToEmpty(x.address()),
                                nullToEmpty(x.category()), Double.toString(x.meters()), Double.toString(x.score())));
                    } else {
                        row.addAll(List.of("", "", "", "", "", ""));
                    }
                }
                row.addAll(List.of("", ""));
                csv.printRecord(row);
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static double round(double score) {
        return Math.round(score * 10_000) / 10_000.0;
    }
}
