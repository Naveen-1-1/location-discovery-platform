package io.github.naveen11.placemap.ingest.pipeline;

/** Timing hooks for one stage, so the pipeline itself stays free of any metrics library. */
public interface StageListener {

    StageListener NONE = new StageListener() {
    };

    /** A worker spent this long processing one item. */
    default void processed(long nanos) {
    }

    /** A worker waited this long to hand its result downstream: time lost to backpressure. */
    default void blocked(long nanos) {
    }
}
