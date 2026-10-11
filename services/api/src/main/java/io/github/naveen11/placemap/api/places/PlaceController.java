package io.github.naveen11.placemap.api.places;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** One place's details (ADR 0042). */
@RestController
@RequestMapping("/api")
public class PlaceController {

    private final PlaceRepository places;
    private final Clock clock;

    public PlaceController(PlaceRepository places, Clock clock) {
        this.places = places;
        this.clock = clock;
    }

    @GetMapping("/places/{id}")
    public PlaceDetails place(@PathVariable long id) {
        return places.details(id, BostonTime.minuteOfWeek(clock))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such place"));
    }
}
