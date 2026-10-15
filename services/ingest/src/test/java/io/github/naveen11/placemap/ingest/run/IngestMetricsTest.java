package io.github.naveen11.placemap.ingest.run;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Once exported to Prometheus, a counter {@code x} becomes {@code x_total} and a timer {@code x} becomes
 * {@code x_seconds}. Two meters that end up with the same name overwrite each other's series, as the counter
 * {@code placemap.ingest.batches} and an older gauge {@code placemap.ingest.batches.total} did.
 */
class IngestMetricsTest {

    @Test
    void noTwoMetersShareAPrometheusName() {
        var registry = new SimpleMeterRegistry();
        var metrics = new IngestMetrics(registry);
        metrics.runFinished("osm", RunCoordinator.Outcome.COMPLETE);
        metrics.limitTripped("osm", "rejects");
        IngestMetrics.RunMetrics run = metrics.forRun("osm");
        run.retried();
        run.recommitted();
        run.geocoded(1, 1, 0, 1);
        run.committed(new MatchedBatch(new ParsedBatch(0, 0, List.of(), List.of(), 0, List.of(), "x"), List.of()));

        Map<String, Set<String>> meterNamesByExportedName = registry.getMeters().stream()
                .collect(groupingBy(m -> exportedName(m.getId()), mapping(m -> m.getId().getName(), toSet())));

        assertThat(meterNamesByExportedName).hasSizeGreaterThan(7)
                .allSatisfy((exported, names) -> assertThat(names).as(exported).hasSize(1));
    }

    private static String exportedName(Meter.Id id) {
        String name = id.getName().replace('.', '_');
        return switch (id.getType()) {
            case COUNTER -> name + "_total";
            case TIMER, LONG_TASK_TIMER -> name + "_seconds";
            default -> name;
        };
    }
}
