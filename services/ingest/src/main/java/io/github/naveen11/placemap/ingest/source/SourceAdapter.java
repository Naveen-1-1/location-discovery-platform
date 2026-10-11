package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    /**
     * Whether several rows may share a key as lines of one record's history (yearly permit renewals, inspection
     * lines). The line with the greatest {@link #recordVersion} then describes the record and the others only add
     * history. Otherwise a repeated key is a duplicate and is rejected.
     */
    default boolean linesPerRecord() {
        return false;
    }

    /** Orders a record's lines when {@link #linesPerRecord()}; compared as text, so ISO-8601 dates work. */
    default String recordVersion(CsvRow row) {
        return "";
    }

    /**
     * The inspection a row reports, if this source carries inspection history (ADR 0034). Throws
     * {@link IllegalArgumentException} with a reason for an unusable line, which is then rejected.
     */
    default Optional<InspectionLine> inspection(CsvRow row) {
        return Optional.empty();
    }

    /**
     * The source whose records this one describes under the same key, such as park features describing Boston's
     * open-space parcels by {@code OS_ID}. Such a record joins the place of the record it describes (the ID join of
     * ADR 0022) and is never scored. Empty for sources matched by name, address and distance.
     */
    default Optional<String> joinsSource() {
        return Optional.empty();
    }

    /**
     * Why this importer cannot run yet, such as a missing API key, or empty. The scheduler then skips the source
     * (and says so once) instead of failing a run every hour.
     */
    default Optional<String> notReady(SourceDefinition source) {
        return Optional.empty();
    }

    /**
     * A marker of the publisher's current data that is cheap to read without downloading it, such as a dataset's
     * last-modified time or a file's ETag. When it matches the last successful run, the download is skipped. Null if
     * the source offers none.
     */
    default String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        return null;
    }
}
