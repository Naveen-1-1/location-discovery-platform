package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.geocode.Geocoding;
import io.github.naveen11.placemap.ingest.snapshot.BatchReader;
import io.github.naveen11.placemap.ingest.snapshot.RawBatch;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import io.github.naveen11.placemap.ingest.source.ParseContext;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.resilience.retry.MethodRetrySpec;
import org.springframework.resilience.retry.SimpleRetryInterceptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The import job. One job instance per run of a source (parameters {@code source} and {@code run}); each attempt at
 * the run is one execution of it, and a resumed attempt is a restart that skips the steps and partitions already done.
 *
 * <ol>
 *   <li><b>fetch</b>: save the snapshot, or end the job if nothing changed ({@link FetchTasklet}).
 *   <li><b>import</b>: split the snapshot's batches into ranges ({@link IngestProperties#partitions()} of them) and run
 *       each as a partition on its own thread. A partition reads its batches one at a time, parses, geocodes and
 *       matches each, and commits it ({@link BatchReader}, {@link BatchProcessor}, {@link BatchWriter}).
 *   <li><b>complete</b>: retire what vanished and refresh the places, on the source lock's connection
 *       ({@link CompleteTasklet}).
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
class ImportJobConfig {

    static final String JOB = "import";
    static final String SOURCE = "source";
    static final String RUN = "run";
    static final String FORCE = "force";

    /**
     * No transaction around a partition's batch: the writer commits each batch in its own transaction. Without
     * synchronization, plain queries in between (the geocode cache) take a connection only while they run, instead of
     * one held for the whole batch. Spring Batch's own updates commit on their own.
     */
    private static final PlatformTransactionManager NO_TRANSACTION = noTransaction();

    /**
     * No transaction around a tasklet either (each manages its own), but with the synchronization tasklet steps need;
     * a tasklet's plain queries share one connection until it ends.
     */
    private static final PlatformTransactionManager TASKLET = new ResourcelessTransactionManager();

    /**
     * Spring Batch writes its bookkeeping (job and step records) at every step and every batch, with no retry of its
     * own, so a dropped connection there would fail the job. Its repository gets Spring's own retry instead, as the
     * writer does: a transient error is retried in place, 200 ms doubling to at most 5 s, 4 times.
     *
     * <p>Not retried: a record that turns out to be written already, when the reply to an earlier commit was lost (a
     * stale version or a duplicate). That fails the step, and {@link RunCoordinator} relaunches the job, which restarts
     * from what is done.
     */
    @Bean
    static BeanPostProcessor retryingJobRepository() {
        var retry = new SimpleRetryInterceptor(new MethodRetrySpec(
                (method, failure) -> DatabaseErrors.isTransient(failure)
                        && !(failure instanceof OptimisticLockingFailureException),
                4, Duration.ofMillis(200), Duration.ofMillis(100), 2, Duration.ofSeconds(5)));
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof JobRepository)) {
                    return bean;
                }
                var proxy = new ProxyFactory(bean);
                proxy.addInterface(JobRepository.class);
                proxy.addAdvice(retry);
                return proxy.getProxy();
            }
        };
    }

    @Bean
    ImportRuns importRuns() {
        return new ImportRuns();
    }

    @Bean
    RunCoordinator runCoordinator(IngestProperties properties, RunStore runs, DataSource dataSource,
            IngestMetrics metrics, JobOperator jobOperator, JobRepository jobRepository, Job importJob,
            ImportRuns importRuns) {
        return new RunCoordinator(properties, runs, dataSource, metrics, jobOperator, jobRepository, importJob,
                importRuns);
    }

    @Bean
    Job importJob(JobRepository jobs, ImportRuns active, Step fetchStep, Step importStep, Step completeStep) {
        return new JobBuilder(JOB, jobs)
                .listener(active)
                .start(fetchStep).on(ExitStatus.NOOP.getExitCode()).end()
                .from(fetchStep).on(ExitStatus.COMPLETED.getExitCode()).to(importStep).next(completeStep)
                .end()
                .build();
    }

    @Bean
    Step fetchStep(JobRepository jobs, IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters,
            SnapshotStore snapshots, RunStore runs) {
        return new StepBuilder("fetch", jobs)
                .tasklet(new FetchTasklet(properties, catalog, adapters, snapshots, runs), TASKLET)
                .build();
    }

    @Bean
    Step importStep(JobRepository jobs, Step batchesStep, Partitioner batchRanges,
            @Qualifier("ingestPartitions") TaskExecutor partitions, IngestProperties properties,
            SourceAdapters adapters, SourcePriority priority, SnapshotStore snapshots, RunStore runs,
            DataSource dataSource, ImportRuns active) {
        return new StepBuilder("import", jobs)
                .listener(new ImportInputs(properties, adapters, priority, snapshots, runs, dataSource, active))
                .partitioner("batches", batchRanges)
                .step(batchesStep)
                .taskExecutor(partitions)
                .gridSize(properties.partitions())
                .build();
    }

    @Bean
    Step batchesStep(JobRepository jobs, BatchReader batchReader, BatchProcessor batchProcessor,
            BatchWriter batchWriter) {
        return new StepBuilder("batches", jobs)
                .<RawBatch, MatchedBatch>chunk(1) // one batch of rows per chunk
                .reader(batchReader)
                .processor(batchProcessor)
                .writer(batchWriter)
                .transactionManager(NO_TRANSACTION)
                .build();
    }

    @Bean
    Step completeStep(JobRepository jobs, IngestProperties properties, SourcePriority priority,
            SnapshotStore snapshots, RunStore runs, ImportRuns active, IngestMetrics metrics) {
        return new StepBuilder("complete", jobs)
                .tasklet(new CompleteTasklet(properties, priority, snapshots, runs, active, metrics), TASKLET)
                .build();
    }

    @Bean
    @StepScope
    Partitioner batchRanges(@Value("#{jobParameters['run']}") long runId, RunStore runs) {
        int totalBatches = runs.find(runId).totalBatches();
        return gridSize -> ImportInputs.ranges(gridSize, totalBatches);
    }

    // Step-scoped beans are declared by their classes (proxied by subclassing), so Spring Batch can look them over for
    // listener annotations; it warns at every step otherwise.
    @Bean
    @StepScope
    BatchReader batchReader(@Value("#{jobParameters['run']}") long runId,
            @Value("#{stepExecutionContext['first']}") int first, @Value("#{stepExecutionContext['end']}") int end,
            ImportRuns active, RunStore runs, IngestProperties properties) {
        ImportRuns.Inputs inputs = active.inputs(runId);
        return new BatchReader(inputs.snapshot().file(), inputs.adapter().columns(), properties.batchSize(),
                inputs.scan(), runs.doneBatches(runId), first, end);
    }

    @Bean
    @StepScope
    BatchProcessor batchProcessor(@Value("#{jobParameters['run']}") long runId,
            @Value("#{jobParameters['source']}") String source, ImportRuns active, CategoryRules categories,
            CoverageArea area, Geocoding geocoding, @Qualifier("geocodeCalls") AsyncTaskExecutor geocodeCalls,
            IngestMetrics metrics) {
        ImportRuns.Inputs inputs = active.inputs(runId);
        var parser = new BatchParser(inputs.adapter(), new ParseContext(categories, area.checker()));
        var geocoder = inputs.adapter().geocodesMissingLocations()
                ? new BatchGeocoder(source, geocoding, geocodeCalls, area.checker(), metrics.forRun(source))
                : null;
        return new BatchProcessor(parser, geocoder, inputs.matcher());
    }

    @Bean
    @StepScope
    BatchWriter batchWriter(@Value("#{jobParameters['run']}") long runId,
            @Value("#{jobParameters['source']}") String source, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, IngestMetrics metrics) {
        return new BatchWriter(jdbc, new TransactionTemplate(transactions), runId, source, metrics.forRun(source));
    }

    private static PlatformTransactionManager noTransaction() {
        var none = new ResourcelessTransactionManager();
        none.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        return none;
    }
}
