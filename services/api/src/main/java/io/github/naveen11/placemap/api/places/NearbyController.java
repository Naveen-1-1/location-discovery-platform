package io.github.naveen11.placemap.api.places;

import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.live.LiveData;
import io.github.naveen11.placemap.api.live.LiveHubClient;
import io.github.naveen11.placemap.api.routing.TravelMode;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
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

@RestController
@RequestMapping("/api")
public class NearbyController {

    /** The straight-line search radius when the client gives none. */
    static final int DEFAULT_RADIUS = 1_500;

    private static final Logger log = LoggerFactory.getLogger(NearbyController.class);

    private final NearbyService nearby;
    private final OriginSnapper snapper;
    private final CategoryCatalog catalog;
    private final NearbyProperties properties;
    private final LiveHubClient live;
    private final Clock clock;

    public NearbyController(NearbyService nearby, OriginSnapper snapper, CategoryCatalog catalog,
            NearbyProperties properties, LiveHubClient live, Clock clock) {
        this.nearby = nearby;
        this.snapper = snapper;
        this.catalog = catalog;
        this.properties = properties;
        this.live = live;
        this.clock = clock;
    }

    /**
     * How the answer's live data went.
     *
     * @param status {@code ok}, or {@code unavailable} when live-hub was slow or down and badges are missing
     * @param topics per live topic, whether its feed is stale and when it last answered
     */
    public record LiveSummary(String status, Map<String, JsonNode> topics) {}

    /**
     * @param lat the snapped origin: the centre of the search's H3 cell, not the position the client sent
     * @param maxSnapErrorMeters how far that position can be from the centre (the UI shows "approx. 70 m")
     * @param mode walk, bike or drive; null for a straight-line search
     * @param travelTimeSource the routing engine that gave the travel times, or {@code straight_line}
     * @param fallback why a search with a mode has no travel times; null otherwise
     * @param driveWithoutTraffic drive times assume empty roads
     * @param live how the live data went; null when no place shown has any
     * @param hiddenForUnknownHours with {@code open=now}: places nearby left out because their hours are unknown
     */
    public record NearbyResponse(
            String snappedCell,
            double lat,
            double lon,
            int maxSnapErrorMeters,
            String mode,
            int radiusMeters,
            String travelTimeSource,
            String fallback,
            boolean driveWithoutTraffic,
            List<NearbyPlace> places,
            LiveSummary live,
            Integer hiddenForUnknownHours) {}

    /**
     * Places near a point. Without {@code mode}: the nearest within {@code radius}, by straight-line distance. With
     * {@code mode}: the nearest candidates within the mode's search radius (capped by {@code radius} if given), sorted
     * by travel time.
     *
     * <p>Filters: {@code snap=true} accepts SNAP/EBT, {@code licensed=true} holds an active licence,
     * {@code inspection=passed} passed its latest inspection, {@code open=now} is open now by its regular hours
     * (places with unknown hours are left out).
     */
    @GetMapping("/places/nearby")
    public NearbyResponse nearby(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) @Min(50) @Max(20_000) Integer radius,
            @RequestParam(name = "category", required = false) List<String> categories,
            @RequestParam(defaultValue = "50") @Min(1) @Max(NearbyService.CANDIDATES) int limit,
            @RequestParam(defaultValue = "false") boolean snap,
            @RequestParam(defaultValue = "false") boolean licensed,
            @RequestParam(required = false) String inspection,
            @RequestParam(required = false) String open) {
        Deadline deadline = Deadline.after(properties.deadline());
        if (inspection != null && !inspection.equals("passed")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inspection must be 'passed'");
        }
        if (open != null && !open.equals("now")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "open must be 'now'");
        }
        int minuteOfWeek = BostonTime.minuteOfWeek(clock);
        var filters = new PlaceFilters(snap, licensed, inspection != null, open == null ? null
                : minuteOfWeek - minuteOfWeek % PlaceFilters.OPEN_AT_STEP_MINUTES);
        TravelMode travelMode = parseMode(mode);
        var wanted = categories == null ? List.<String>of() : categories;
        var unknown = wanted.stream().filter(c -> !catalog.isKnown(c)).collect(Collectors.toCollection(TreeSet::new));
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category: " + String.join(", ", unknown));
        }
        int searchRadius = travelMode == null
                ? (radius == null ? DEFAULT_RADIUS : radius)
                : Math.min(radius == null ? Integer.MAX_VALUE : radius, NearbyService.searchRadius(travelMode));
        var query = new NearbyQuery(snapper.snap(lat, lon), travelMode, wanted, searchRadius, filters);

        NearbyResult result = await(nearby.nearby(query, deadline));

        // "Open now" for this minute, not the shared result's: it is worked out per answer, like live data.
        List<NearbyPlace> shown = result.places().subList(0, Math.min(limit, result.places().size())).stream()
                .map(p -> p.at(minuteOfWeek))
                .toList();
        LiveSummary liveSummary = null;
        List<String> liveKeys = shown.stream().map(NearbyPlace::liveKey).filter(Objects::nonNull).distinct().toList();
        if (!liveKeys.isEmpty()) {
            // Fresh for every answer, never from the shared result: live counts change by the minute.
            LiveData data = live.fetch(liveKeys, deadline);
            shown = shown.stream()
                    .map(p -> p.liveKey() == null ? p : p.withLive(data.items().get(p.liveKey())))
                    .toList();
            liveSummary = new LiveSummary(data.status(), data.topics());
        }
        // Never log coordinates or the cell (privacy): the trace id on this line links it to the request's trace.
        log.atInfo()
                .addKeyValue("mode", travelMode == null ? "straight_line" : travelMode.param())
                .addKeyValue("radius_m", searchRadius)
                .addKeyValue("categories", wanted.isEmpty() ? "all" : String.join(",", query.categories()))
                .addKeyValue("filters", filters.names())
                .addKeyValue("travel_time_source", result.travelTimeSource())
                .addKeyValue("fallback", result.fallback() == null ? "none" : result.fallback())
                .addKeyValue("results", shown.size())
                .addKeyValue("live", liveSummary == null ? "none" : liveSummary.status())
                .log("nearby search");
        SnappedOrigin origin = query.origin();
        return new NearbyResponse(origin.cell(), origin.lat(), origin.lon(), origin.maxErrorMeters(),
                travelMode == null ? null : travelMode.param(), searchRadius, result.travelTimeSource(),
                result.fallback(), travelMode == TravelMode.DRIVE, shown, liveSummary, result.hiddenForUnknownHours());
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return catalog.all();
    }

    private static TravelMode parseMode(String mode) {
        if (mode == null) {
            return null;
        }
        try {
            return TravelMode.fromParam(mode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Overload answers 503 with Retry-After: the database is saturated, or the deadline passed. */
    private static NearbyResult await(CompletableFuture<NearbyResult> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BulkheadFullException) {
                throw unavailable("The database is busy. Try again in a moment.", cause);
            }
            if (cause instanceof TimeoutException) {
                throw unavailable("The search took too long. Try again in a moment.", cause);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private static ErrorResponseException unavailable(String detail, Throwable cause) {
        var error = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, cause);
        error.setDetail(detail);
        error.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        return error;
    }
}
