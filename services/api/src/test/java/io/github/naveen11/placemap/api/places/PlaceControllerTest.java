package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** A place's details: its facts and sources, or 404 for a place that does not exist or was retired. */
@WebMvcTest(PlaceController.class)
@Import(NearbyControllerTest.Properties.class)
class PlaceControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    PlaceRepository places;

    @Test
    void answersWithTheFactsJudgedAtTheCurrentMinute() {
        var facts = new PlaceDetails.Facts(true, true,
                new PlaceDetails.Inspection("passed", OffsetDateTime.parse("2026-07-16T19:52:22Z"), 0),
                new PlaceDetails.Hours("Mon–Fri 07:00–23:00; Sat–Sun closed", "osm", true),
                List.of(new PlaceDetails.Feature("Playground", 1)));
        when(places.details(eq(42L), eq(842))).thenReturn(Optional.of(new PlaceDetails(42, "Tatte", "food",
                "1 Main St", null, null, 42.356, -71.066, null, facts, List.of("boston_food_inspections", "osm"))));

        var result = mvc.get().uri("/api/places/42").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.facts.hours.openNow").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.facts.inspection.result").isEqualTo("passed");
        assertThat(result).bodyJson().extractingPath("$.sources[1]").isEqualTo("osm");
    }

    @Test
    void anUnknownPlaceIs404() {
        when(places.details(eq(7L), anyInt())).thenReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/places/7")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
