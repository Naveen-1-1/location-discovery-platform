package io.github.naveen11.placemap.ingest.source;

/** What parsing one snapshot row produced. */
public sealed interface RowResult {

    record Accepted(SourceRow row) implements RowResult {
    }

    /** The row failed validation; it goes to {@code ingest.reject} and counts toward the 2% limit (ADR 0020). */
    record Rejected(String reason) implements RowResult {
    }

    /**
     * The row is valid but out of scope (outside the four towns, no app category, permanently closed). It is not
     * stored and is not a defect, so it does not count toward the reject limit.
     */
    record Skipped(String reason) implements RowResult {
    }

    static RowResult accepted(SourceRow row) {
        return new Accepted(row);
    }

    static RowResult rejected(String reason) {
        return new Rejected(reason);
    }

    static RowResult skipped(String reason) {
        return new Skipped(reason);
    }
}
