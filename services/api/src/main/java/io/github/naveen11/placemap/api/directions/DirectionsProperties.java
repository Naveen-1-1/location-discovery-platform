package io.github.naveen11.placemap.api.directions;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.directions.*}: routes and isochrones, which a user asks for on purpose and can wait a few seconds
 * for.
 *
 * @param deadline how long a request may take in total
 * @param timeout how long the engine call may take
 * @param routeTtl how long a route is shared
 * @param isochroneTtl how long isochrones are shared; reachable areas change even more rarely than routes
 * @param maxMinutes the longest isochrone limit a client may ask for
 */
@ConfigurationProperties("placemap.directions")
public record DirectionsProperties(
        @DefaultValue("6s") Duration deadline,
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("10m") Duration routeTtl,
        @DefaultValue("30m") Duration isochroneTtl,
        @DefaultValue("1000") int cacheSize,
        @DefaultValue("30") int maxMinutes) {}
