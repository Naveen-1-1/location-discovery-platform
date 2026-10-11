package io.github.naveen11.placemap.ingest.snapshot;

/** What a snapshot row contributes, decided once by {@link SnapshotScan} before any batch is cut. */
public enum RowRole {
    /** The row describes its record (and adds history, if the source has any). */
    RECORD,
    /** An older line of a record that another row describes: history only. */
    HISTORY_ONLY,
    /** A repeat of a key that already appeared, in a source where keys must be unique: rejected. */
    DUPLICATE
}
