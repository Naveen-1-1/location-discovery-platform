package io.github.naveen11.placemap.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 10 requests per second per client, bursts of 20, no raw IP addresses kept. */
class ClientRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final ClientRateLimiter limiter = new ClientRateLimiter(new RateLimitProperties(true, 10, 20, 1_000),
            new TimeMeter() {
                @Override
                public long currentTimeNanos() {
                    return nanos.get();
                }

                @Override
                public boolean isWallClockBased() {
                    return false;
                }
            });

    @Test
    void aBurstOfTwentyThenTenPerSecond() {
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("203.0.113.7").allowed()).as("request %d", i + 1).isTrue();
        }
        ClientRateLimiter.Decision over = limiter.tryAcquire("203.0.113.7");
        assertThat(over.allowed()).isFalse();
        assertThat(over.retryAfterSeconds()).isEqualTo(1);

        nanos.addAndGet(Duration.ofMillis(100).toNanos());
        assertThat(limiter.tryAcquire("203.0.113.7").allowed()).as("one token after 100 ms").isTrue();
        assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isFalse();

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("203.0.113.7").allowed()).as("full again after 2 s").isTrue();
        }
    }

    @Test
    void oneClientOverItsLimitDoesNotAffectAnother() {
        for (int i = 0; i < 25; i++) {
            limiter.tryAcquire("203.0.113.7");
        }

        assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isFalse();
        assertThat(limiter.tryAcquire("198.51.100.4").allowed()).isTrue();
    }

    @Test
    void clientsAreKeptAsSaltedHashesNotAddresses() {
        String key = limiter.clientKey("203.0.113.7");

        assertThat(key).hasSize(32).doesNotContain("203").isEqualTo(limiter.clientKey("203.0.113.7"));
        assertThat(limiter.clientKey("203.0.113.8")).isNotEqualTo(key);
        var otherProcess = new ClientRateLimiter(new RateLimitProperties(true, 10, 20, 1_000),
                TimeMeter.SYSTEM_NANOTIME);
        assertThat(otherProcess.clientKey("203.0.113.7")).as("the salt differs per process").isNotEqualTo(key);
    }

    @Test
    void aFloodOfDistinctClientsCannotGrowTheMapPastItsLimit() {
        for (int i = 0; i < 5_000; i++) {
            limiter.tryAcquire("10.0." + (i / 256) + "." + (i % 256));
        }

        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(1_000);
    }
}
