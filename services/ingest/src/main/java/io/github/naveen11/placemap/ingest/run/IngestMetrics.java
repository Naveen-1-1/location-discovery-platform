package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import io.github.naveen11.placemap.concurrent.ContiguousWatermark;
import io.github.naveen11.placemap.ingest.pipeline.StageListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ingest metrics for the Ingest dashboard. Labels are only {@code source}, {@code stage}, {@code queue} and
 * {@code outcome}, all low-cardinality, to stay far below Grafana Cloud's free series limit.
 */
public class IngestMetrics {

    private final MeterRegistry registry;

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void runFinished(String source, RunCoordinator.Outcome outcome) {
        registry.counter("placemap.ingest.runs", "source", source, "outcome", outcome.name().toLowerCase()).increment();
    }

    RunMetrics forRun(String source) {
        return new RunMetrics(source);
    }

    /** Meters for one run; gauges are removed when the run ends. */
    final class RunMetrics implements AutoCloseable {
        private final String source;
        private final Counter accepted;
        private final Counter rejected;
        private final Counter skipped;
        private final Counter batches;
        private final Counter retries;
        private final List<Gauge> gauges = new ArrayList<>();

        private RunMetrics(String source) {
            this.source = source;
            this.accepted = registry.counter("placemap.ingest.rows", "source", source, "outcome", "accepted");
            this.rejected = registry.counter("placemap.ingest.rows", "source", source, "outcome", "rejected");
            this.skipped = registry.counter("placemap.ingest.rows", "source", source, "outcome", "skipped");
            this.batches = registry.counter("placemap.ingest.batches", "source", source);
            this.retries = registry.counter("placemap.ingest.batch.retries", "source", source);
        }

        void committed(ParsedBatch batch) {
            accepted.increment(batch.records().size());
            rejected.increment(batch.rejects().size());
            skipped.increment(batch.skipped());
            batches.increment();
        }

        void retried() {
            retries.increment();
        }

        StageListener stage(String stage) {
            Timer process = Timer.builder("placemap.ingest.stage.process")
                    .description("Time to process one batch")
                    .tags("source", source, "stage", stage)
                    .register(registry);
            Timer blocked = Timer.builder("placemap.ingest.stage.blocked")
                    .description("Time spent waiting for room in the next stage's queue (backpressure)")
                    .tags("source", source, "stage", stage)
                    .register(registry);
            return new StageListener() {
                @Override
                public void processed(long nanos) {
                    process.record(nanos, TimeUnit.NANOSECONDS);
                }

                @Override
                public void blocked(long nanos) {
                    blocked.record(nanos, TimeUnit.NANOSECONDS);
                }
            };
        }

        /** @param queue the stage that consumes from the channel */
        void watchQueue(String queue, BoundedChannel<?> channel) {
            gauges.add(Gauge.builder("placemap.ingest.queue.size", channel, BoundedChannel::size)
                    .description("Batches waiting in a stage's input queue")
                    .tags("source", source, "queue", queue)
                    .register(registry));
            gauges.add(Gauge.builder("placemap.ingest.queue.capacity", channel, BoundedChannel::capacity)
                    .tags("source", source, "queue", queue)
                    .register(registry));
        }

        void watchProgress(ContiguousWatermark watermark, int totalBatches) {
            gauges.add(Gauge.builder("placemap.ingest.batches.committed", watermark, w -> w.current() + 1)
                    .description("Batches 0..N-1 all committed (the contiguous watermark)")
                    .tags("source", source)
                    .register(registry));
            gauges.add(Gauge.builder("placemap.ingest.batches.total", () -> totalBatches)
                    .tags("source", source)
                    .register(registry));
        }

        @Override
        public void close() {
            gauges.forEach(registry::remove);
        }
    }
}
