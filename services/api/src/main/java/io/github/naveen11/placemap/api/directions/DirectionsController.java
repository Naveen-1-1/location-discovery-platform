package io.github.naveen11.placemap.api.directions;

import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.directions.DirectionsService.IsochroneKey;
import io.github.naveen11.placemap.api.directions.DirectionsService.IsochroneResult;
import io.github.naveen11.placemap.api.directions.DirectionsService.RouteKey;
import io.github.naveen11.placemap.api.directions.DirectionsService.RouteResult;
import io.github.naveen11.placemap.api.directions.DirectionsService.UnknownPlace;
import io.github.naveen11.placemap.api.places.OriginSnapper;
import io.github.naveen11.placemap.api.places.SnappedOrigin;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.github.naveen11.placemap.api.routing.TravelMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Routes and isochrones as GeoJSON, ready for a map layer. Both start from the centre of the caller's H3 cell,
 * so the exact position never reaches the routing engine.
 */
@RestController
@RequestMapping("/api")
public class DirectionsController {

    private static final Logger log = LoggerFactory.getLogger(DirectionsController.class);

    private final DirectionsService directions;
    private final OriginSnapper snapper;
    private final DirectionsProperties properties;

    public DirectionsController(DirectionsService directions, OriginSnapper snapper, DirectionsProperties properties) {
        this.directions = directions;
        this.snapper = snapper;
        this.properties = properties;
    }

    /** A GeoJSON Feature: the route's LineString, with what the UI shows about it as properties. */
    public record RouteFeature(String type, JsonNode geometry, RouteProperties properties) {}

    public record RouteProperties(
            long placeId,
            String placeName,
            @Schema(allowableValues = {"walk", "bike", "drive"}) String mode,
            long seconds,
            long meters,
            String travelTimeSource,
            boolean driveWithoutTraffic,
            String snappedCell,
            int maxSnapErrorMeters) {}

    /** A GeoJSON FeatureCollection, one area per limit, smallest first; the other members describe the request. */
    public record IsochroneCollection(
            String type,
            @Schema(allowableValues = {"walk", "bike", "drive"}) String mode,
            String travelTimeSource,
            boolean driveWithoutTraffic,
            String snappedCell,
            int maxSnapErrorMeters,
            List<IsochroneFeature> features) {}

    public record IsochroneFeature(String type, JsonNode geometry, IsochroneProperties properties) {}

    public record IsochroneProperties(int minutes) {}

    @GetMapping("/route")
    public RouteFeature route(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam long place,
            @RequestParam(defaultValue = "walk") String mode) {
        Deadline deadline = Deadline.after(properties.deadline());
        TravelMode travelMode = parseMode(mode);
        SnappedOrigin origin = snapper.snap(lat, lon);

        RouteResult result = await(directions.route(new RouteKey(origin, place, travelMode), deadline),
                refused -> "No " + travelMode.param() + " route found from here to this place.");

        log.atInfo().addKeyValue("mode", travelMode.param()).log("route");
        var route = result.route();
        return new RouteFeature("Feature", route.geometry(), new RouteProperties(place, result.place().name(),
                travelMode.param(), route.seconds(), route.meters(), result.travelTimeSource(),
                travelMode == TravelMode.DRIVE, origin.cell(), origin.maxErrorMeters()));
    }

    @GetMapping("/isochrone")
    public IsochroneCollection isochrone(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam(defaultValue = "walk") String mode,
            @RequestParam(defaultValue = "5,10,15") List<Integer> minutes) {
        Deadline deadline = Deadline.after(properties.deadline());
        TravelMode travelMode = parseMode(mode);
        if (minutes.isEmpty() || minutes.size() > 3
                || minutes.stream().anyMatch(m -> m == null || m < 1 || m > properties.maxMinutes())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give one to three limits of 1 to " + properties.maxMinutes() + " minutes.");
        }
        SnappedOrigin origin = snapper.snap(lat, lon);

        IsochroneResult result = await(directions.isochrones(new IsochroneKey(origin, travelMode, minutes), deadline),
                refused -> "Nothing can be reached from here by " + travelMode.param() + ".");

        log.atInfo().addKeyValue("mode", travelMode.param()).addKeyValue("limits", minutes.size()).log("isochrone");
        List<IsochroneFeature> features = result.areas().stream()
                .map(area -> new IsochroneFeature("Feature", area.geometry(), new IsochroneProperties(area.minutes())))
                .toList();
        return new IsochroneCollection("FeatureCollection", travelMode.param(), result.travelTimeSource(),
                travelMode == TravelMode.DRIVE, origin.cell(), origin.maxErrorMeters(), features);
    }

    private static TravelMode parseMode(String mode) {
        try {
            return TravelMode.fromParam(mode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Unknown places and "no route" answer 404. An engine that is down, refusing, slow or over quota, and a missed
     * deadline, answer 503 with Retry-After: unlike nearby there is no fallback to show.
     */
    private static <T> T await(CompletableFuture<T> future, Function<GuardedRouting.Refused, String> noRoute) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownPlace unknown) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage());
            }
            if (cause instanceof GuardedRouting.Refused refused) {
                if (refused.unroutable()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, noRoute.apply(refused));
                }
                throw unavailable("Directions are unavailable right now (" + refused.reason() + "). Try again soon.",
                        "breaker_open".equals(refused.reason()) ? 10 : 5, cause);
            }
            if (cause instanceof TimeoutException) {
                throw unavailable("Directions took too long. Try again soon.", 5, cause);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private static ErrorResponseException unavailable(String detail, int retryAfterSeconds, Throwable cause) {
        var error = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, cause);
        error.setDetail(detail);
        error.getHeaders().set(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfterSeconds));
        return error;
    }
}
