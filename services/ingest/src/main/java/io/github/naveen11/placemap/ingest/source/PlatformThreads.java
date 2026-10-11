package io.github.naveen11.placemap.ingest.source;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs native (JNI) code such as DuckDB on a platform thread. A virtual thread that enters native code pins its
 * carrier thread for the whole call, so the plan keeps JNI off virtual threads; the run itself lives on one.
 */
final class PlatformThreads {

    @FunctionalInterface
    interface Task {
        void run() throws Exception;
    }

    private PlatformThreads() {
    }

    static void run(String name, Task task) throws IOException, InterruptedException {
        var failure = new AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().name(name).start(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            thread.interrupt(); // best effort: native code may not notice
            throw e;
        }
        switch (failure.get()) {
            case null -> {
            }
            case IOException e -> throw e;
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            case Throwable t -> throw new IOException(name + " failed: " + t.getMessage(), t);
        }
    }
}
