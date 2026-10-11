package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Everything the generic pipeline needs to know about one source. Every snapshot is a CSV file whose header is exactly
 * {@link #columns()}, in a stable row order, so the same input always gives the same batches (ADR 0020).
 */
public interface SourceAdapter {

    /** The id in {@code data/sources.yaml}. */
    String sourceId();

    /** The snapshot's columns, in order. Never contains the source's {@code drop_fields} (ADR 0031). */
    List<String> columns();

    /** Writes the snapshot to {@code target}, requesting only {@link #columns()} from the publisher. */
    void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException;

    /** The row's stable identity within the source, or null if it has none (the row is then rejected). */
    String key(CsvRow row);

    /** Turns one row into a record or a rejection. Runs on parse workers; must not keep state between calls. */
    RowResult parse(CsvRow row, ParseContext context);
}
