package io.github.naveen11.placemap.api.routing;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.routing.*}.
 *
 * @param engine which routing engine answers: the hosted openrouteservice, or our own Valhalla
 */
@ConfigurationProperties("placemap.routing")
public record RoutingProperties(
        @DefaultValue("ors") Engine engine,
        @DefaultValue Ors ors,
        @DefaultValue Valhalla valhalla) {

    public enum Engine {
        ORS,
        VALHALLA
    }

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

    /**
     * Our own Valhalla with Massachusetts tiles (tools/build-valhalla-tiles.sh).
     *
     * @param matrixTimeout the plan's 800 ms for an engine on the same network
     * @param timeout routes and isochrones
     * @param maxSnapMeters the same rule as for openrouteservice, so both engines reach the same places
     */
    public record Valhalla(
            @DefaultValue("http://localhost:8002") URI baseUrl,
            @DefaultValue("800ms") Duration matrixTimeout,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("350") double maxSnapMeters) {}
}
