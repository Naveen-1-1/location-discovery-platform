package io.github.naveen11.placemap.ingest.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Weighted precision and recall over an evenly sampled set, and the blind check's agreement. */
class MatchEvaluationTest {

    private static MatchEvaluation.Labelled c(String band, double score, int label) {
        return new MatchEvaluation.Labelled("x", "s", band, score, label);
    }

    @Test
    void eachBandCountsForItsShareOfAllRecords() {
        // Two cases per band; the link band stands for 1,000 records, the low band for 100.
        List<MatchEvaluation.Labelled> cases = List.of(
                c("link", 0.95, 1), c("link", 0.85, 0),   // one right link, one wrong
                c("low", 0.50, 1), c("low", 0.40, 0));    // one missed match
        var points = MatchEvaluation.curve(cases, Map.of("link", 1_000, "low", 100));

        MatchEvaluation.Point at080 = points.stream().filter(p -> p.threshold() == 0.80).findFirst().orElseThrow();
        assertThat(at080.precision()).isCloseTo(0.5, offset(1e-9));          // 500 right of 1,000 linked
        assertThat(at080.recall()).isCloseTo(500.0 / 550, offset(1e-9));     // 50 missed from the low band
        assertThat(at080.linked()).isCloseTo(1_000.0 / 1_100, offset(1e-9));

        MatchEvaluation.Point at090 = points.stream().filter(p -> p.threshold() == 0.90).findFirst().orElseThrow();
        assertThat(at090.precision()).isEqualTo(1.0);
        assertThat(at090.recall()).isCloseTo(500.0 / 550, offset(1e-9));
    }

    @Test
    void aMatchWithAnotherCandidateIsAWrongLinkAndAMiss() {
        var points = MatchEvaluation.curve(List.of(c("link", 0.9, 2)), Map.of("link", 10));
        MatchEvaluation.Point p = points.stream().filter(x -> x.threshold() == 0.80).findFirst().orElseThrow();
        assertThat(p.precision()).isZero();
        assertThat(p.recall()).isZero();
    }

    @Test
    void theReportReadsTheSampleItsMetaAndTheOwnersBlindLabels(@TempDir Path dir) throws Exception {
        Path sample = dir.resolve("match-sample.csv");
        var rows = new ArrayList<String>();
        rows.add(String.join(",", MatchSample.HEADER));
        rows.add(row("m001", "1", "link", 0.92, "1"));
        rows.add(row("m002", "1", "review", 0.65, "0"));
        rows.add(row("m003", "0", "low", 0.40, ""));
        Files.write(sample, rows);
        Files.writeString(MatchSample.metaPath(sample), "{\"bands\": {\"link\": 40, \"review\": 10, \"low\": 50, \"none\": 7}}");
        Path owner = dir.resolve("owner.csv");
        Files.writeString(owner, "case_id,label\nm001,1\nm002,1\n");

        String report = MatchEvaluation.report(sample, owner);

        assertThat(report).contains("Labelled cases: 2 of the sample", "| link | 40 | 1 | 1 | 0 | 0 |",
                "| no candidate | 7 |", "| 0.80 | 1.000 | 1.000 |",
                "Blind check: 2 cases labelled by both, 1 agree (50.0%)", "- m002 (Claude 0, owner 1)");
    }

    private static String row(String id, String blind, String band, double score, String label) {
        var values = new ArrayList<>(List.of(id, blind, "overture_places", "k", "Cafe", "food", "1 Main St", band,
                Double.toString(score)));
        for (int k = 0; k < MatchSample.CANDIDATES * 6; k++) {
            values.add("");
        }
        values.add(label);
        values.add("");
        return String.join(",", values);
    }
}
