package io.github.naveen11.placemap.ingest.geocode;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * What every run's geocode workers share: the service, the cache, and the two limits on calls (plan: "Photon calls run
 * on virtual threads under Semaphore(4)"). The semaphore caps calls in flight; the rate limiter keeps their rate within
 * the service's terms (public Photon asks for fair use), whatever the number of workers and runs.
 */
public final class Geocoding {

    private final Geocoder geocoder;
    private final GeocodeCache cache;
    private final Semaphore calls;
    private final RateLimiter rate;
    private final int maxConcurrentCalls;

    public Geocoding(Geocoder geocoder, GeocodeCache cache, int maxConcurrentCalls, double callsPerSecond) {
        this.geocoder = geocoder;
        this.cache = cache;
        this.calls = new Semaphore(maxConcurrentCalls);
        this.maxConcurrentCalls = maxConcurrentCalls;
        // Whole calls per period: 1/s is one per second, 0.5/s one every two seconds, 4/s four per second.
        boolean slow = callsPerSecond < 1;
        this.rate = RateLimiter.of("geocoder", RateLimiterConfig.custom()
                .limitForPeriod(slow ? 1 : (int) Math.round(callsPerSecond))
                .limitRefreshPeriod(slow ? Duration.ofMillis(Math.round(1000 / callsPerSecond)) : Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMinutes(10))
                .build());
    }

    public GeocodeCache cache() {
        return cache;
    }

    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    /** One call to the service, within both limits. Blocks a virtual thread while it waits for either. */
    public List<Candidate> search(String query) throws IOException, InterruptedException {
        calls.acquire();
        try {
            if (!rate.acquirePermission()) {
                if (Thread.currentThread().isInterrupted()) { // the limiter gives up quietly when interrupted
                    throw new InterruptedException("interrupted while waiting for the geocoder's rate limit");
                }
                throw new IOException("waited 10 minutes for the geocoder's rate limit");
            }
            return geocoder.search(query);
        } finally {
            calls.release();
        }
    }
}
