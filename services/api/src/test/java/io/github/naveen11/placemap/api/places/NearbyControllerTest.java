package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(NearbyController.class)
class NearbyControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    PlaceRepository places;

    @BeforeEach
    void knownCategories() {
        when(places.categories()).thenReturn(List.of(new Category("food", "Food & drink"), new Category("gym", "Gyms")));
    }

    @Test
    void returnsNearbyPlacesSortedByStraightLine() {
        when(places.nearby(42.355, -71.0656, 800, List.of("food"), 20)).thenReturn(List.of(
                new NearbyPlace(7, "Tatte", "food", "1 Main St", 42.356, -71.066, 112)));

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656&radius=800&category=food&limit=20");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.travelTimeSource").isEqualTo("straight_line");
        assertThat(result).bodyJson().extractingPath("$.places[0].name").isEqualTo("Tatte");
        assertThat(result).bodyJson().extractingPath("$.places[0].distanceMeters").isEqualTo(112);
    }

    @Test
    void rejectsOutOfRangeLatitudeAsProblemDetail() {
        var result = mvc.get().uri("/api/places/nearby?lat=100&lon=-71.06");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        verify(places, never()).nearby(anyDouble(), anyDouble(), anyInt(), anyList(), anyInt());
    }

    @Test
    void rejectsRadiusAboveLimit() {
        assertThat(mvc.get().uri("/api/places/nearby?lat=42.35&lon=-71.06&radius=50000")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownCategory() {
        var result = mvc.get().uri("/api/places/nearby?lat=42.35&lon=-71.06&category=food&category=casino");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Unknown category: casino");
    }
}
