package io.github.naveen11.placemap.ingest.match;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Precision and recall of the link threshold on the labelled sample (ADR 0022; proposed exit: precision ≥ 0.95,
 * recall ≥ 0.85).
 *
 * <p>A case's label names the candidate that is the same real place as the record ({@code 1}–{@code 3}) or none
 * ({@code 0}). At threshold t the matcher links the best candidate when its score is at least t. A link is right only
 * if the label is {@code 1}; a record whose real place is another candidate, or that is not linked, is a miss. Each
 * band's cases count for the records of that band in the whole population (sampled evenly, weighted back), so the
 * numbers describe all evaluated records, not the sample. Recall is among records with a candidate within 100 m.
 */
public final class MatchEvaluation {

    /** Every 0.05, and every 0.01 around the link threshold (0.81, before that 0.80), where the targets are decided. */
    static final double[] THRESHOLDS = {0.60, 0.65, 0.70, 0.75, 0.80, 0.81, 0.82, 0.83, 0.84, 0.85, 0.90};

    record Labelled(String caseId, String source, String band, double bestScore, int label) {
    }

    record Point(double threshold, double precision, double recall, double linked) {
    }

    private MatchEvaluation() {
    }

    /** The labelled cases of a sample file; unlabelled ones are left out. */
    static List<Labelled> read(Path sample) throws IOException {
        var cases = new ArrayList<Labelled>();
        try (Reader reader = Files.newBufferedReader(sample, StandardCharsets.UTF_8);
                CSVParser csv = CSVParser.parse(reader, CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get())) {
            for (CSVRecord row : csv) {
                String label = row.get("label").strip();
                if (label.isEmpty()) {
                    continue;
                }
                cases.add(new Labelled(row.get("case_id"), row.get("source"), row.get("band"),
                        Double.parseDouble(row.get("best_score")), Integer.parseInt(label)));
            }
        }
        return cases;
    }

    /** case_id → label, from a file with at least those two columns (the owner's blind labels). */
    static Map<String, Integer> labels(Path file) throws IOException {
        var labels = new TreeMap<String, Integer>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                CSVParser csv = CSVParser.parse(reader, CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get())) {
            for (CSVRecord row : csv) {
                String label = row.get("label").strip();
                if (!label.isEmpty()) {
                    labels.put(row.get("case_id"), Integer.parseInt(label));
                }
            }
        }
        return labels;
    }

    static List<Point> curve(List<Labelled> cases, Map<String, Integer> population) {
        var sampled = new TreeMap<String, Integer>();
        cases.forEach(c -> sampled.merge(c.band(), 1, Integer::sum));
        var points = new ArrayList<Point>();
        for (double t : THRESHOLDS) {
            double tp = 0;
            double fp = 0;
            double fn = 0;
            double all = 0;
            double linked = 0;
            for (Labelled c : cases) {
                double weight = population.getOrDefault(c.band(), 0) / (double) sampled.get(c.band());
                boolean link = c.bestScore() >= t;
                all += weight;
                linked += link ? weight : 0;
                if (link && c.label() == 1) {
                    tp += weight;
                } else {
                    if (link) {
                        fp += weight;
                    }
                    if (c.label() >= 1) {
                        fn += weight;
                    }
                }
            }
            points.add(new Point(t, tp + fp == 0 ? 1 : tp / (tp + fp), tp + fn == 0 ? 1 : tp / (tp + fn),
                    all == 0 ? 0 : linked / all));
        }
        return points;
    }

    /** The report printed by {@code tools/dev.sh match-evaluate}, in Markdown. */
    public static String report(Path sample, Path ownerLabels) throws IOException {
        List<Labelled> cases = read(sample);
        JsonNode meta = new JsonMapper().readTree(MatchSample.metaPath(sample).toFile());
        var population = new LinkedHashMap<String, Integer>();
        meta.required("bands").properties().forEach(e -> population.put(e.getKey(), e.getValue().asInt()));
        var out = new StringBuilder();
        out.append("Labelled cases: ").append(cases.size()).append(" of the sample\n\n");
        out.append("| band | records | labelled | same place as the best candidate | another candidate | none |\n");
        out.append("|---|---:|---:|---:|---:|---:|\n");
        for (String band : MatchSample.BANDS) {
            List<Labelled> in = cases.stream().filter(c -> c.band().equals(band)).toList();
            out.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d |%n", band,
                    population.getOrDefault(band, 0), in.size(), in.stream().filter(c -> c.label() == 1).count(),
                    in.stream().filter(c -> c.label() >= 2).count(), in.stream().filter(c -> c.label() == 0).count()));
        }
        out.append(String.format(Locale.ROOT, "| no candidate | %d | – | – | – | – |%n%n", population.getOrDefault("none", 0)));
        out.append("| link at score ≥ | precision | recall | records linked |\n|---:|---:|---:|---:|\n");
        for (Point p : curve(cases, population)) {
            out.append(String.format(Locale.ROOT, "| %.2f | %.3f | %.3f | %.1f%% |%n", p.threshold(), p.precision(),
                    p.recall(), 100 * p.linked()));
        }
        if (ownerLabels != null && Files.exists(ownerLabels)) {
            Map<String, Integer> theirs = labels(ownerLabels);
            if (theirs.isEmpty()) {
                out.append("\nBlind check: ").append(ownerLabels).append(" has no answers yet\n");
                return out.toString();
            }
            var mine = new TreeMap<String, Integer>();
            cases.forEach(c -> mine.put(c.caseId(), c.label()));
            var disagreements = new ArrayList<String>();
            int both = 0;
            for (var entry : theirs.entrySet()) {
                Integer my = mine.get(entry.getKey());
                if (my == null) {
                    continue;
                }
                both++;
                if (!my.equals(entry.getValue())) {
                    disagreements.add(entry.getKey() + " (Claude " + my + ", owner " + entry.getValue() + ")");
                }
            }
            out.append(String.format(Locale.ROOT, "%nBlind check: %d cases labelled by both, %d agree (%.1f%%)%n", both,
                    both - disagreements.size(), both == 0 ? 0 : 100.0 * (both - disagreements.size()) / both));
            disagreements.forEach(d -> out.append("- ").append(d).append('\n'));
        }
        return out.toString();
    }
}
