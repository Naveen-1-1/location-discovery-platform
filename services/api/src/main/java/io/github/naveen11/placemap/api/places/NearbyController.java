package io.github.naveen11.placemap.api.places;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class NearbyController {

    /** Phase 1 sorts by straight-line distance only; Phase 3 adds routed travel times (ADR 0026). */
    static final String TRAVEL_TIME_SOURCE = "straight_line";

    private final PlaceRepository places;

    public NearbyController(PlaceRepository places) {
        this.places = places;
    }

    public record NearbyResponse(
            double lat, double lon, int radiusMeters, String travelTimeSource, List<NearbyPlace> places) {}

    @GetMapping("/places/nearby")
    public NearbyResponse nearby(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam(defaultValue = "1500") @Min(50) @Max(5000) int radius,
            @RequestParam(name = "category", required = false) List<String> categories,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        var wanted = categories == null ? List.<String>of() : categories.stream().distinct().toList();
        var known = places.categories().stream().map(Category::code).collect(Collectors.toSet());
        var unknown = wanted.stream().filter(c -> !known.contains(c)).collect(Collectors.toCollection(TreeSet::new));
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category: " + String.join(", ", unknown));
        }
        return new NearbyResponse(lat, lon, radius, TRAVEL_TIME_SOURCE, places.nearby(lat, lon, radius, wanted, limit));
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return places.categories();
    }
}
