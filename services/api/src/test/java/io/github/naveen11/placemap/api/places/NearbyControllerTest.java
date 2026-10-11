package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naveen11.placemap.api.live.LiveData;
import io.github.naveen11.placemap.api.live.LiveHubClient;
import io.github.naveen11.placemap.api.routing.TravelMode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(NearbyController.class)
@Import({CategoryCatalog.class, OriginSnapper.class, NearbyControllerTest.Properties.class})
class NearbyControllerTest {

    private static final JsonMapper JSON = new JsonMapper();

    /** Monday 12 October 2026, 14:02 in Boston (EDT): minute 842 of the week. */
    static final Instant MONDAY_1402 = Instant.parse("2026-10-12T18:02:00Z");

    @TestConfiguration
    @EnableConfigurationProperties(NearbyProperties.class)
    static class Properties {

        @Bean
        Clock clock() {
            return Clock.fixed(MONDAY_1402, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    PlaceRepository places;

    @MockitoBean
    NearbyService nearby;

    @MockitoBean
    LiveHubClient live;

    @BeforeEach
    void knownCategories() {
        when(places.categories()).thenReturn(List.of(new Category("food", "Food & drink"), new Category("gym", "Gyms")));
    }

    @Test
    void aStraightLineSearchUsesTheSnappedCellAndTheGivenRadius() {
        answer("straight_line", null,
                new NearbyPlace(7, "Tatte", "food", "1 Main St", 42.356, -71.066, 112, null, null));

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656&radius=800&category=food&limit=20").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.travelTimeSource").isEqualTo("straight_line");
        assertThat(result).bodyJson().extractingPath("$.mode").isNull();
        assertThat(result).bodyJson().extractingPath("$.snappedCell").asString().startsWith("8a");
        assertThat(result).bodyJson().extractingPath("$.maxSnapErrorMeters").asNumber().satisfies(
                n -> assertThat(n.intValue()).isBetween(60, 85));
        assertThat(result).bodyJson().extractingPath("$.places[0].name").isEqualTo("Tatte");
        assertThat(result).bodyJson().extractingPath("$.places[0].travelSeconds").isNull();
        NearbyQuery asked = askedQuery();
        assertThat(asked.mode()).isNull();
        assertThat(asked.radiusMeters()).isEqualTo(800);
        assertThat(asked.categories()).containsExactly("food");
    }

    @Test
    void aWalkingSearchUsesTheModesRadiusAndReturnsTravelTimes() {
        answer("openrouteservice", null,
                new NearbyPlace(3, "Near", "food", null, 42.356, -71.066, 300, 240L, 310L),
                new NearbyPlace(4, "Farther", "food", null, 42.357, -71.067, 200, 600L, 800L),
                new NearbyPlace(5, "Third", "food", null, 42.358, -71.068, 400, 700L, 900L));

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656&mode=walk&limit=2").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.mode").isEqualTo("walk");
        assertThat(result).bodyJson().extractingPath("$.radiusMeters").isEqualTo(2_500);
        assertThat(result).bodyJson().extractingPath("$.driveWithoutTraffic").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.places.length()").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.places[0].travelSeconds").isEqualTo(240);
        assertThat(askedQuery().mode()).isEqualTo(TravelMode.WALK);
    }

    @Test
    void aDrivingSearchIsLabelledWithoutTrafficAndARadiusOnlyNarrowsIt() {
        answer("openrouteservice", null);

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656&mode=drive&radius=5000").exchange();

        assertThat(result).bodyJson().extractingPath("$.driveWithoutTraffic").isEqualTo(true);
        assertThat(askedQuery().radiusMeters()).isEqualTo(5_000);
    }

    @Test
    void aFallbackIsFlagged() {
        answer("straight_line", "timeout");

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656&mode=bike").exchange();

        assertThat(result).bodyJson().extractingPath("$.travelTimeSource").isEqualTo("straight_line");
        assertThat(result).bodyJson().extractingPath("$.fallback").isEqualTo("timeout");
    }

    @Test
    void aBusyDatabaseAnswers503WithRetryAfter() {
        when(nearby.nearby(any(), any())).thenReturn(CompletableFuture.failedFuture(
                BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("database"))));

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656&mode=walk").exchange();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, "1");
    }

    @Test
    void aMissedDeadlineAnswers503WithRetryAfter() {
        when(nearby.nearby(any(), any())).thenReturn(CompletableFuture.failedFuture(new TimeoutException()));

        assertThat(mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656").exchange())
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasHeader(HttpHeaders.RETRY_AFTER, "1");
    }

    @Test
    void rejectsAnUnknownMode() {
        var result = mvc.get().uri("/api/places/nearby?lat=42.35&lon=-71.06&mode=teleport").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("walk, bike or drive");
    }

    @Test
    void rejectsOutOfRangeLatitudeAsProblemDetail() {
        var result = mvc.get().uri("/api/places/nearby?lat=100&lon=-71.06").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        verify(nearby, never()).nearby(any(), any());
    }

    @Test
    void rejectsRadiusAboveLimit() {
        assertThat(mvc.get().uri("/api/places/nearby?lat=42.35&lon=-71.06&radius=50000")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownCategory() {
        var result = mvc.get().uri("/api/places/nearby?lat=42.35&lon=-71.06&category=food&category=casino").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Unknown category: casino");
    }

    /** Makes the service answer with these places, whatever the query; {@link #askedQuery()} shows the query. */
    @Test
    void docksGetTheirLiveCountsFreshForEachAnswer() {
        answer("straight_line", null,
                dock(11, "bikes:st-1"),
                new NearbyPlace(12, "Tatte", "food", null, 42.356, -71.066, 140, null, null),
                dock(13, "bikes:st-2"));
        when(live.fetch(any(), any())).thenReturn(new LiveData("ok",
                Map.of("bikes", JSON.readTree("{\"stale\":false,\"updated\":1700}")),
                Map.of("bikes:st-1", JSON.readTree("{\"id\":\"st-1\",\"bikes\":4,\"docks\":11}"))));

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.live.status").isEqualTo("ok");
        assertThat(result).bodyJson().extractingPath("$.live.topics.bikes.stale").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.places[0].liveKey").isEqualTo("bikes:st-1");
        assertThat(result).bodyJson().extractingPath("$.places[0].live.bikes").isEqualTo(4);
        assertThat(result).bodyJson().extractingPath("$.places[1].live").isNull();
        assertThat(result).bodyJson().extractingPath("$.places[2].live").as("live-hub had nothing for st-2").isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(live).fetch(keys.capture(), any());
        assertThat(keys.getValue()).containsExactly("bikes:st-1", "bikes:st-2");
    }

    @Test
    void whenLiveHubDoesNotAnswerThePlacesComeWithoutBadges() {
        answer("straight_line", null, dock(11, "bikes:st-1"));
        when(live.fetch(any(), any())).thenReturn(LiveData.unavailable());

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.live.status").isEqualTo("unavailable");
        assertThat(result).bodyJson().extractingPath("$.places[0].liveKey").isEqualTo("bikes:st-1");
        assertThat(result).bodyJson().extractingPath("$.places[0].live").isNull();
    }

    @Test
    void placesWithoutLiveDataNeverCallLiveHub() {
        answer("straight_line", null, new NearbyPlace(12, "Tatte", "food", null, 42.356, -71.066, 140, null, null));

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656").exchange();

        assertThat(result).bodyJson().extractingPath("$.live").isNull();
        verify(live, never()).fetch(any(), any());
    }

    @Test
    void filtersBecomePartOfTheSharedQueryAndOpenNowIsBucketedToFiveMinutes() {
        answer("straight_line", null);

        assertThat(mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656&snap=true&inspection=passed&open=now"))
                .hasStatusOk();

        assertThat(askedQuery().filters()).isEqualTo(new PlaceFilters(true, false, true, 840));
    }

    @Test
    void withoutFiltersTheQueryHasNone() {
        answer("straight_line", null);

        assertThat(mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656")).hasStatusOk();

        assertThat(askedQuery().filters()).isEqualTo(PlaceFilters.NONE);
    }

    @Test
    void rejectsUnknownFilterValues() {
        assertThat(mvc.get().uri("/api/places/nearby?lat=42.35&lon=-71.06&open=tomorrow")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/places/nearby?lat=42.35&lon=-71.06&inspection=failed"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        verify(nearby, never()).nearby(any(), any());
    }

    @Test
    void openNowIsJudgedForEachAnswerNotTakenFromTheSharedResult() {
        // Monday 14:00–14:30 only, and a place with unknown hours.
        var halfHour = new PlaceFacts(true, true, "passed", null, OpenWeek.parse("{[840,870)}"));
        answer("straight_line", null,
                new NearbyPlace(1, "Cafe", "food", null, 42.356, -71.066, 50, null, null, null, null, halfHour),
                new NearbyPlace(2, "Unknown", "food", null, 42.356, -71.066, 60, null, null));

        var result = mvc.get().uri("/api/places/nearby?lat=42.355&lon=-71.0656").exchange();

        assertThat(result).bodyJson().extractingPath("$.places[0].facts.openNow").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.places[0].facts.acceptsSnap").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.places[0].facts.inspection").isEqualTo("passed");
        assertThat(result).bodyJson().as("the week is not sent").doesNotHavePath("$.places[0].facts.week");
        assertThat(result).bodyJson().extractingPath("$.places[1].facts.openNow").isNull();
    }

    private static NearbyPlace dock(long id, String liveKey) {
        return new NearbyPlace(id, "Dock " + id, "bike_share", null, 42.356, -71.066, 100 + id, null, null, liveKey, null);
    }

    private void answer(String source, String fallback, NearbyPlace... found) {
        when(nearby.nearby(any(), any())).thenAnswer(invocation -> CompletableFuture.completedFuture(
                new NearbyResult(invocation.getArgument(0), source, fallback, List.of(found))));
    }

    private NearbyQuery askedQuery() {
        var captor = ArgumentCaptor.forClass(NearbyQuery.class);
        verify(nearby).nearby(captor.capture(), any());
        return captor.getValue();
    }
}
