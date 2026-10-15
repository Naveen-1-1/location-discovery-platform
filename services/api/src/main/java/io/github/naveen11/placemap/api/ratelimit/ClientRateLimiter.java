package io.github.naveen11.placemap.api.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.caffeine.Bucket4jCaffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Per-client request limits: one token bucket per client, refilled at {@code perSecond} up to {@code burst}, kept by
 * Bucket4j in a bounded Caffeine cache so a flood of distinct clients cannot exhaust memory.
 *
 * <p>A client is identified by a salted SHA-256 hash of its IP address, so the raw address is never stored. The salt
 * is random per process, so a hash means nothing outside it.
 *
 * <p>A bucket left alone for as long as it takes to refill completely is no different from a new one, so idle buckets
 * expire after that time (plus a second).
 */
public class ClientRateLimiter {

    /** Whether the request may go ahead and, if not, how many whole seconds until the client may try again. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private final byte[] salt = new byte[32];
    private final BucketConfiguration limit;
    private final CaffeineProxyManager<String> buckets;

    public ClientRateLimiter(RateLimitProperties properties, TimeMeter time) {
        new SecureRandom().nextBytes(salt);
        this.limit = BucketConfiguration.builder()
                .addLimit(l -> l.capacity(properties.burst()).refillGreedy(properties.perSecond(), Duration.ofSeconds(1)))
                .build();
        this.buckets = Bucket4jCaffeine.<String>builderFor(Caffeine.newBuilder().maximumSize(properties.maxClients()))
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(1)))
                .clientClock(time)
                .build();
    }

    public Decision tryAcquire(String clientAddress) {
        ConsumptionProbe probe = buckets.builder().build(clientKey(clientAddress), limit).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, 0);
        }
        // Retry-After counts whole seconds, so round up.
        return new Decision(false, Math.max(1, Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L)));
    }

    /** Clients currently tracked; for tests and metrics. */
    long trackedClients() {
        buckets.getCache().cleanUp();
        return buckets.getCache().estimatedSize();
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
}
