package io.github.naveen11.placemap.api.routing;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.routing.*}.
 *
 * @param concurrency the routing bulkhead: calls to the engine in flight at once
 */
@ConfigurationProperties("placemap.routing")
public record RoutingProperties(@DefaultValue("8") int concurrency, @DefaultValue Ors ors) {

    /**
     * The hosted openrouteservice API.
     *
     * @param apiKey from ORS_API_KEY in .env; without it every travel time falls back to straight-line distance
     * @param matrixTimeout the travel-time call runs inside nearby's 1.5 s deadline
     * @param timeout routes and isochrones, which a user waits for on purpose
     * @param maxSnapMeters a target the engine had to move further than this to reach a path counts as unreachable;
     *     350 m is the radius openrouteservice's own directions endpoint allows
     */
    public record Ors(
            @DefaultValue("https://api.openrouteservice.org") URI baseUrl,
            String apiKey,
            @DefaultValue("1s") Duration matrixTimeout,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("350") double maxSnapMeters) {}
}
