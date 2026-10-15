package io.github.naveen11.placemap.api.places;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.nearby.*}, defaults from the plan (showcase 3).
 *
 * @param deadline how long a request may take in total
 * @param travelTimeTimeout how long the one travel-time call may take before the result falls back to straight-line
 *     (read by the {@code travel-times} time limiter's settings)
 * @param cacheTtl how long a result with travel times is shared
 * @param fallbackTtl how long a straight-line fallback is shared: long enough to absorb a burst while the engine is
 *     down, short enough that travel times come back soon after it recovers
 */
@ConfigurationProperties("placemap.nearby")
public record NearbyProperties(
        @DefaultValue("1500ms") Duration deadline,
        @DefaultValue("800ms") Duration travelTimeTimeout,
        @DefaultValue("10000") int cacheSize,
        @DefaultValue("5m") Duration cacheTtl,
        @DefaultValue("15s") Duration fallbackTtl) {}
