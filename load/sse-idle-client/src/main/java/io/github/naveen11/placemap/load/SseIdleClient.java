package io.github.naveen11.placemap.load;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Holds thousands of live-hub streams open, one virtual thread each, the way browsers hold an EventSource, and
 * measures how late changes arrive: each change carries its publication time ({@code "t"}), and the stack's
 * containers share one clock. Load tests L6 (idle streams), L7 (slow readers), L8 (the thread comparison) and crash
 * test C5 (live-hub killed) use it; tools/load-tests.py runs it in the stack.
 *
 * <ul>
 *   <li>Each stream comes from its own address (X-Forwarded-For from 198.19.0.0/16), as live-hub limits streams per
 *       client.
 *   <li>A stream that ends or fails reconnects after the delay the server last sent ({@code retry:}, 2 to 7 s), as
 *       EventSource does; how long each took to get a fresh snapshot is recorded.
 *   <li>Slow readers (a share of the streams) read their first snapshot, then nothing: a 4 KB receive buffer fills,
 *       live-hub's writes stall, and its watchdog should evict them. At the end each is checked for being closed.
 * </ul>
 *
 * <p>Usage: {@code java -jar sse-idle-client.jar --url http://live-hub:8081/live?topics=bikes --clients 5000
 * --ramp 250 --duration 600 [--slow-share 0.01] [--out /work/L6.sse.json]}. Lag counts from the end of the ramp.
 */
public final class SseIdleClient {

    record Options(URI url, int clients, int ramp, Duration duration, double slowShare, Path out, Duration window) {

        static Options parse(String[] args) {
            URI url = URI.create("http://live-hub:8081/live?topics=bikes");
            int clients = 1000;
            int ramp = 250;
            Duration duration = Duration.ofMinutes(10);
            double slowShare = 0;
            Path out = null;
            for (int i = 0; i + 1 < args.length; i += 2) {
                String value = args[i + 1];
                switch (args[i]) {
                    case "--url" -> url = URI.create(value);
                    case "--clients" -> clients = Integer.parseInt(value);
                    case "--ramp" -> ramp = Integer.parseInt(value);
                    case "--duration" -> duration = Duration.ofSeconds(Long.parseLong(value));
                    case "--slow-share" -> slowShare = Double.parseDouble(value);
                    case "--out" -> out = Path.of(value);
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            return new Options(url, clients, ramp, duration, slowShare, out, Duration.ofSeconds(10));
        }
    }

    /** Lag in whole milliseconds up to 30 s, the last bucket holding anything later. */
    static final class LagHistogram {
        private final AtomicLongArray counts = new AtomicLongArray(30_001);

        void record(long millis) {
            counts.incrementAndGet((int) Math.max(0, Math.min(millis, counts.length() - 1)));
        }

        long[] snapshot() {
            long[] copy = new long[counts.length()];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = counts.get(i);
            }
            return copy;
        }

        static long[] minus(long[] a, long[] b) {
            long[] d = new long[a.length];
            for (int i = 0; i < a.length; i++) {
                d[i] = a[i] - b[i];
            }
            return d;
        }

        static long count(long[] c) {
            long n = 0;
            for (long v : c) {
                n += v;
            }
            return n;
        }

        /** The smallest lag at or above the given share of samples, or -1 without samples. */
        static long percentile(long[] c, double share) {
            long n = count(c);
            if (n == 0) {
                return -1;
            }
            long rank = (long) Math.ceil(share * n);
            long seen = 0;
            for (int i = 0; i < c.length; i++) {
                seen += c[i];
                if (seen >= Math.max(1, rank)) {
                    return i;
                }
            }
            return c.length - 1;
        }

        static long max(long[] c) {
            for (int i = c.length - 1; i >= 0; i--) {
                if (c[i] > 0) {
                    return i;
                }
            }
            return -1;
        }
    }

    static final class Stats {
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger peakOpen = new AtomicInteger();
        final AtomicLong attempts = new AtomicLong();
        final AtomicLong refused = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong ends = new AtomicLong();
        final AtomicLong snapshots = new AtomicLong();
        final AtomicLong resyncs = new AtomicLong();
        final AtomicLong events = new AtomicLong();
        final LagHistogram lag = new LagHistogram();
        final ConcurrentLinkedQueue<Long> recoveries = new ConcurrentLinkedQueue<>();
        final AtomicInteger unrecovered = new AtomicInteger();
        final AtomicInteger slowEvicted = new AtomicInteger();
        final AtomicInteger slowOpen = new AtomicInteger();
        volatile boolean steady;
        volatile boolean stopping;
        volatile boolean slowChecked; // slow readers keep their sockets until checked

        void opened() {
            peakOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
        }
    }

    private final Options options;
    private final Stats stats = new Stats();
    private final List<Stream> streams = new ArrayList<>();
    private int openAtEnd;

    SseIdleClient(Options options) {
        this.options = options;
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        String summary = new SseIdleClient(options).run();
        if (options.out() != null) {
            Files.writeString(options.out(), summary);
        }
        System.out.println(summary);
    }

    String run() throws InterruptedException {
        int slowEvery = options.slowShare() > 0 ? (int) Math.round(1 / options.slowShare()) : 0;
        long start = System.nanoTime();
        long rampNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, options.ramp());
        var windows = new ArrayList<String>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < options.clients(); i++) {
                var stream = new Stream(i, slowEvery > 0 && i % slowEvery == slowEvery / 2);
                streams.add(stream);
                threads.submit(stream);
                long due = start + (i + 1) * rampNanos;
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
            }
            log("opened %d streams in %.1f s; %d open", options.clients(), (System.nanoTime() - start) / 1e9,
                    stats.open.get());
            TimeUnit.SECONDS.sleep(10); // the last snapshots go out
            stats.steady = true;
            long steadyStart = System.nanoTime();
            long[] previous = stats.lag.snapshot();
            long previousEvents = stats.events.get();
            long end = steadyStart + options.duration().toNanos();
            while (System.nanoTime() < end) {
                long next = Math.min(end, System.nanoTime() + options.window().toNanos());
                TimeUnit.NANOSECONDS.sleep(Math.max(0, next - System.nanoTime()));
                long[] now = stats.lag.snapshot();
                long[] window = LagHistogram.minus(now, previous);
                long events = stats.events.get();
                String line = String.format(Locale.ROOT,
                        "{\"t\":%d,\"open\":%d,\"events\":%d,\"p50\":%d,\"p99\":%d,\"max\":%d}",
                        Math.round((System.nanoTime() - steadyStart) / 1e9), stats.open.get(), events - previousEvents,
                        LagHistogram.percentile(window, 0.5), LagHistogram.percentile(window, 0.99),
                        LagHistogram.max(window));
                windows.add(line);
                log("%s", line);
                previous = now;
                previousEvents = events;
            }
            openAtEnd = stats.open.get(); // before the client closes them itself
            stats.stopping = true;
            streams.stream().filter(s -> !s.slow).forEach(Stream::close);
            try (ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor()) {
                streams.stream().filter(s -> s.slow).forEach(s -> checks.submit(s::checkEvicted));
            }
            stats.slowChecked = true;
            streams.forEach(Stream::close);
            threads.shutdownNow();
        }
        for (Stream s : streams) {
            if (!s.slow && s.disconnectedAt != 0) {
                stats.unrecovered.incrementAndGet(); // ended and never got a fresh snapshot before the end
            }
        }
        return summary(windows);
    }

    private String summary(List<String> windows) {
        long[] lag = stats.lag.snapshot();
        List<Long> recoveries = new ArrayList<>(stats.recoveries);
        recoveries.sort(null);
        long within30 = recoveries.stream().filter(ms -> ms <= 30_000).count();
        return String.format(Locale.ROOT, """
                {"url":"%s","clients":%d,"slow":%d,"duration_s":%d,
                 "peak_open":%d,"open_at_end":%d,"attempts":%d,"refused":%d,"failures":%d,"ends":%d,
                 "snapshots":%d,"resyncs":%d,"events":%d,
                 "lag_ms":{"count":%d,"p50":%d,"p95":%d,"p99":%d,"p999":%d,"max":%d},
                 "recoveries":{"count":%d,"within_30s":%d,"p50_ms":%d,"p99_ms":%d,"max_ms":%d,"unrecovered":%d},
                 "slow_clients":{"evicted":%d,"still_open":%d},
                 "windows":[%s]}
                """,
                options.url(), options.clients(), streams.stream().filter(s -> s.slow).count(),
                options.duration().toSeconds(), stats.peakOpen.get(), openAtEnd, stats.attempts.get(),
                stats.refused.get(), stats.failures.get(), stats.ends.get(), stats.snapshots.get(), stats.resyncs.get(),
                stats.events.get(), LagHistogram.count(lag), LagHistogram.percentile(lag, 0.5),
                LagHistogram.percentile(lag, 0.95), LagHistogram.percentile(lag, 0.99),
                LagHistogram.percentile(lag, 0.999), LagHistogram.max(lag), recoveries.size(), within30,
                at(recoveries, 0.5), at(recoveries, 0.99), recoveries.isEmpty() ? -1 : recoveries.getLast(),
                stats.unrecovered.get(), stats.slowEvicted.get(), stats.slowOpen.get(), String.join(",\n  ", windows));
    }

    private static long at(List<Long> sorted, double share) {
        return sorted.isEmpty() ? -1 : sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(share * sorted.size()) - 1));
    }

    private static void log(String format, Object... args) {
        System.err.printf(Locale.ROOT, "[sse-idle-client] " + format + "%n", args);
    }

    /** One stream, reconnected until the run ends; or, when slow, one stream that stops reading. */
    final class Stream implements Runnable {
        final int index;
        final boolean slow;
        private volatile Socket socket;
        private volatile InputStream slowInput;
        volatile long disconnectedAt; // nanoTime when a stream that had its snapshot ended; 0 once recovered
        private long retryMillis = 3000;

        Stream(int index, boolean slow) {
            this.index = index;
            this.slow = slow;
        }

        @Override
        public void run() {
            while (!stats.stopping && !Thread.currentThread().isInterrupted()) {
                boolean hadSnapshot = false;
                try (Socket s = new Socket()) {
                    socket = s;
                    if (slow) {
                        s.setReceiveBufferSize(4096);
                    }
                    stats.attempts.incrementAndGet();
                    s.connect(new InetSocketAddress(options.url().getHost(), options.url().getPort()), 5000);
                    s.setSoTimeout(slow ? 0 : 30_000); // heartbeats come every 15 s
                    request(s.getOutputStream());
                    var reader = new SseReader(s.getInputStream());
                    int status = reader.readHead();
                    if (status != 200) {
                        stats.refused.incrementAndGet();
                        retryMillis = reader.retryAfterSeconds() > 0 ? reader.retryAfterSeconds() * 1000 : retryMillis;
                        throw new IOException("refused with " + status);
                    }
                    for (SseReader.Event e = reader.next(); e != null; e = reader.next()) {
                        if (e.name().equals("snapshot")) {
                            stats.snapshots.incrementAndGet();
                            if (hadSnapshot) {
                                stats.resyncs.incrementAndGet();
                            } else {
                                hadSnapshot = true;
                                stats.opened();
                                long down = disconnectedAt;
                                if (down != 0) {
                                    stats.recoveries.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - down));
                                    disconnectedAt = 0;
                                }
                                if (slow) {
                                    slowInput = s.getInputStream();
                                    waitForTheEnd(); // reads nothing more: the buffers fill and live-hub stalls
                                    return;
                                }
                            }
                        } else {
                            long published = SseReader.publishedMillis(e.data());
                            if (published > 0) {
                                stats.events.incrementAndGet();
                                if (stats.steady) {
                                    stats.lag.record(System.currentTimeMillis() - published);
                                }
                            }
                        }
                        if (reader.retryMillis() > 0) {
                            retryMillis = reader.retryMillis();
                        }
                    }
                    stats.ends.incrementAndGet();
                } catch (IOException | RuntimeException e) {
                    if (stats.stopping) {
                        return;
                    }
                    if (hadSnapshot) {
                        stats.ends.incrementAndGet();
                    } else if (!(e.getMessage() != null && e.getMessage().startsWith("refused"))) {
                        stats.failures.incrementAndGet();
                    }
                } finally {
                    if (hadSnapshot && !slow) {
                        stats.open.decrementAndGet();
                        if (!stats.stopping && disconnectedAt == 0) {
                            disconnectedAt = System.nanoTime();
                        }
                    }
                }
                if (slow) {
                    return; // a slow reader that failed to connect simply does not count
                }
                try {
                    // EventSource waits the server's retry delay; a little jitter keeps reconnections apart.
                    Thread.sleep(retryMillis + ThreadLocalRandom.current().nextLong(250));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private void request(OutputStream out) throws IOException {
            URI url = options.url();
            String path = url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + url.getHost() + ":" + url.getPort() + "\r\n"
                    + "Accept: text/event-stream\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "X-Forwarded-For: 198.19." + ((index >> 8) & 255) + "." + (index & 255) + "\r\n"
                    + "User-Agent: placemap-sse-idle-client\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private void waitForTheEnd() {
            try {
                while (!stats.slowChecked) {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** For a slow reader: whether live-hub closed it. Drains what was buffered; open means data still flows. */
        void checkEvicted() {
            InputStream in = slowInput;
            Socket s = socket;
            if (in == null || s == null) {
                return;
            }
            try {
                s.setSoTimeout(2000);
                byte[] buffer = new byte[65536];
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    if (in.read(buffer) < 0) {
                        stats.slowEvicted.incrementAndGet();
                        return;
                    }
                }
                stats.slowOpen.incrementAndGet();
            } catch (SocketTimeoutException e) {
                stats.slowOpen.incrementAndGet(); // open but quiet: not closed by the server
            } catch (IOException e) {
                stats.slowEvicted.incrementAndGet(); // reset by the server
            }
        }

        void close() {
            Socket s = socket;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }
}
