package io.github.naveen11.placemap.ingest.geocode;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * What every run's geocoding shares: the service, the cache, and the limit on the rate of calls, which keeps it within
 * the service's terms (public Photon asks for fair use) whatever the number of partitions and runs. The calls in
 * flight are capped by the executor they run on.
 */
public final class Geocoding {

    private final Geocoder geocoder;
    private final GeocodeCache cache;
    private final RateLimiter rate;

    public Geocoding(Geocoder geocoder, GeocodeCache cache, double callsPerSecond) {
        this.geocoder = geocoder;
        this.cache = cache;
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

    /** One call to the service, within the rate limit. Blocks (a virtual thread) while it waits for a permit. */
    public List<Candidate> search(String query) throws IOException, InterruptedException {
        if (!rate.acquirePermission()) {
            if (Thread.currentThread().isInterrupted()) { // the limiter gives up quietly when interrupted
                throw new InterruptedException("interrupted while waiting for the geocoder's rate limit");
            }
            throw new IOException("waited 10 minutes for the geocoder's rate limit");
        }
        return geocoder.search(query);
    }
}
