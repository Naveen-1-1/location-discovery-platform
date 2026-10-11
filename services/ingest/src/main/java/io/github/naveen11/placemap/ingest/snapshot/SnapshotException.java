package io.github.naveen11.placemap.ingest.snapshot;

import java.io.Serial;

/**
 * The saved input is unusable (missing, changed since its batches were cut, or with unexpected columns). Retrying the
 * same run cannot help, so the run fails and the next one starts from a fresh download.
 */
public class SnapshotException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SnapshotException(String message) {
        super(message);
    }
}
