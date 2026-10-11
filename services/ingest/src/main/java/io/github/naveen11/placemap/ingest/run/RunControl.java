package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.pipeline.Pipeline;
import java.util.concurrent.CancellationException;

/** Lets the scheduler stop one run: gracefully first (end of stream), then hard (fail and interrupt). */
public final class RunControl {

    private volatile boolean stopRequested;
    private volatile Pipeline pipeline;
    private volatile Thread runThread;

    void attach(Thread thread) {
        runThread = thread;
    }

    void attach(Pipeline running) {
        pipeline = running;
        if (stopRequested) {
            running.requestStop();
        }
    }

    public boolean stopRequested() {
        return stopRequested;
    }

    /** Stop reading new batches; batches already in the pipeline are still committed. */
    public void requestStop() {
        stopRequested = true;
        Pipeline current = pipeline;
        if (current != null) {
            current.requestStop();
        }
    }

    /** Abandon the run now. The batch in flight rolls back and is redone on resume. */
    public void cancel() {
        stopRequested = true;
        Pipeline current = pipeline;
        if (current != null) {
            current.cancel(new CancellationException("ingest is shutting down"));
        }
        Thread thread = runThread;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
