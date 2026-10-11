package io.github.naveen11.placemap.ingest.pipeline;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs native (JNI) code such as DuckDB or H3 on a platform thread. A virtual thread that enters native code pins its
 * carrier thread for the whole call, so the plan keeps JNI off virtual threads; a run itself lives on one.
 */
public final class PlatformThreads {

    @FunctionalInterface
    public interface Task {
        void run() throws Exception;
    }

    private PlatformThreads() {
    }

    public static void run(String name, Task task) throws IOException, InterruptedException {
        call(name, () -> {
            task.run();
            return null;
        });
    }

    public static <T> T call(String name, Callable<T> task) throws IOException, InterruptedException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().name(name).start(() -> {
            try {
                result.set(task.call());
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
        return result.get();
    }
}
