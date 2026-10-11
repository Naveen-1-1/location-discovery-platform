package io.github.naveen11.placemap.api.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Per-client request limits (ADR 0026): one token bucket per client, refilled at {@code perSecond} up to
 * {@code burst}, held in a bounded map so a flood of distinct clients cannot exhaust memory.
 *
 * <p>A client is identified by a salted SHA-256 hash of its IP address (its user id once sign-in exists, Phase 6), so
 * the raw address is never stored. The salt is random per process, so a hash means nothing outside it.
 *
 * <p>A bucket left alone for as long as it takes to refill completely is no different from a new one, so idle buckets
 * are dropped after that time.
 */
public class ClientRateLimiter {

    /** Whether the request may go ahead and, if not, how many whole seconds until the client may try again. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private final int perSecond;
    private final int burst;
    private final TimeMeter time;
    private final byte[] salt = new byte[32];
    private final Cache<String, Bucket> buckets;

    public ClientRateLimiter(RateLimitProperties properties, TimeMeter time) {
        this.perSecond = properties.perSecond();
        this.burst = properties.burst();
        this.time = time;
        new SecureRandom().nextBytes(salt);
        long refillNanos = Duration.ofSeconds(1).toNanos() * burst / perSecond;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.maxClients())
                .expireAfterAccess(Duration.ofNanos(refillNanos).plusSeconds(1))
                .build();
    }

    public Decision tryAcquire(String clientAddress) {
        Bucket bucket = buckets.get(clientKey(clientAddress), key -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, 0);
        }
        long seconds = Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1;
        return new Decision(false, seconds);
    }

    /** Clients currently tracked; for tests and metrics. */
    long trackedClients() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    String clientKey(String clientAddress) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return HexFormat.of().formatHex(digest.digest(clientAddress.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(burst).refillGreedy(perSecond, Duration.ofSeconds(1)))
                .withCustomTimePrecision(time)
                .build();
    }
}
