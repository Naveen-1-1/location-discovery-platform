package io.github.naveen11.placemap.api.places;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.nearby.*}, defaults from the plan (showcase 3) and ADR 0025/0026.
 *
 * @param deadline how long a request may take in total
 * @param travelTimeTimeout how long the one travel-time call may take before the result falls back to straight-line
 * @param cacheTtl how long a result with travel times is shared
 * @param fallbackTtl how long a straight-line fallback is shared: long enough to absorb a burst while the engine is
 *     down, short enough that travel times come back soon after it recovers
 * @param databaseConcurrency the database bulkhead: as many permits as the connection pool has connections
 */
@ConfigurationProperties("placemap.nearby")
public record NearbyProperties(
        @DefaultValue("1500ms") Duration deadline,
        @DefaultValue("800ms") Duration travelTimeTimeout,
        @DefaultValue("10000") int cacheSize,
        @DefaultValue("5m") Duration cacheTtl,
        @DefaultValue("15s") Duration fallbackTtl,
        @DefaultValue("10") int databaseConcurrency) {}
