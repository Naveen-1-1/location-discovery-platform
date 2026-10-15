package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.match.Matcher;
import io.github.naveen11.placemap.ingest.match.PlaceIndex;
import io.github.naveen11.placemap.ingest.snapshot.Snapshot;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotException;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotScan;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Prepares the import step on the job's thread, before any partition starts, and splits its batches:
 *
 * <ul>
 *   <li>Checks the run's saved snapshot is the one it fetched and that the batch size is unchanged, scans it, and
 *       loads the frozen place index to match against; every partition then shares these through {@link ImportRuns}.
 *   <li>Splits the batches into {@code grid size} contiguous ranges, one per partition. A restarted step keeps its
 *       first grid size and ranges (Spring Batch saves them), and each range redoes only its uncommitted batches.
 * </ul>
 */
final class ImportInputs implements StepExecutionListener {

    static final String FIRST = "first";
    static final String END = "end";

    private static final Logger log = LoggerFactory.getLogger(ImportInputs.class);

    private final IngestProperties properties;
    private final SourceAdapters adapters;
    private final SourcePriority priority;
    private final SnapshotStore snapshots;
    private final RunStore runs;
    private final DataSource dataSource;
    private final ImportRuns active;

    ImportInputs(IngestProperties properties, SourceAdapters adapters, SourcePriority priority, SnapshotStore snapshots,
            RunStore runs, DataSource dataSource, ImportRuns active) {
        this.properties = properties;
        this.adapters = adapters;
        this.priority = priority;
        this.snapshots = snapshots;
        this.runs = runs;
        this.dataSource = dataSource;
        this.active = active;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        JobParameters parameters = stepExecution.getJobParameters();
        String sourceId = parameters.getString(ImportJobConfig.SOURCE);
        long runId = parameters.getLong(ImportJobConfig.RUN);
        RunRecord run = runs.find(runId);
        SourceAdapter adapter = adapters.get(sourceId);
        try {
            Snapshot snapshot = snapshots.existing(sourceId, runId)
                    .filter(s -> s.sha256().equals(run.snapshotSha256()))
                    .orElseThrow(() -> new SnapshotException("the saved snapshot of run " + runId
                            + " is missing or changed"));
            SnapshotScan scan = SnapshotScan.of(snapshot.file(), adapter);
            if (scan.batches(properties.batchSize()) != run.totalBatches()) {
                throw new SnapshotException("batch size changed since run " + runId + " started");
            }
            log.info("ingest {}: run {} has {} rows in {} batches, {} already committed", sourceId, runId, scan.rows(),
                    run.totalBatches(), runs.doneBatches(runId).size());
            PlaceIndex index = PlaceIndex.load(dataSource, sourceId, runId, adapter.joinsSource().orElse(null));
            log.info("ingest {}: matching against {} places", sourceId, index.size());
            var matcher = new Matcher(index, priority, sourceId);
            active.loaded(runId, new ImportRuns.Inputs(adapter, snapshot, scan, matcher));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        active.unloaded(stepExecution.getJobParameters().getLong(ImportJobConfig.RUN));
        return null;
    }

    /** The import step's partitions: contiguous ranges of the run's batches, never an empty one. */
    static Map<String, ExecutionContext> ranges(int gridSize, int totalBatches) {
        int parts = Math.max(1, Math.min(gridSize, totalBatches));
        var ranges = new HashMap<String, ExecutionContext>();
        for (int i = 0; i < parts; i++) {
            var context = new ExecutionContext();
            context.putInt(FIRST, (int) ((long) totalBatches * i / parts));
            context.putInt(END, (int) ((long) totalBatches * (i + 1) / parts));
            ranges.put("partition" + i, context);
        }
        return ranges;
    }
}
