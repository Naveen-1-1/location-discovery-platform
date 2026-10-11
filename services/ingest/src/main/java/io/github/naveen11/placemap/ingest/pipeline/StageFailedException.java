package io.github.naveen11.placemap.ingest.pipeline;

import java.io.Serial;

/** The first error in a pipeline, with the stage it happened in. */
public class StageFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String stage;

    public StageFailedException(String stage, Throwable cause) {
        super("stage '" + stage + "' failed: " + cause, cause);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
