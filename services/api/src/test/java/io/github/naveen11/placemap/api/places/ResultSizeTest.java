package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.api.routing.RoutingClient.Leg;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultSizeTest {

    private static final NearbyQuery QUERY = new NearbyQuery(new SnappedOrigin("8a2a3066...", 42.355, -71.0656, 75),
            null, List.of(), 1_500);

    @Test
    void anEmptyResultCostsItsFixedPart() {
        assertThat(ResultSize.bytes(result(List.of()))).isEqualTo(ResultSize.RESULT + 16);
    }

    @Test
    void eachPlaceAddsItsObjectAndItsStrings() {
        NearbyPlace shortName = place("Cafe", null);
        NearbyPlace longName = place("Cafe with a much longer name on a long street", null);

        int one = ResultSize.bytes(result(List.of(shortName)));
        int two = ResultSize.bytes(result(List.of(shortName, shortName)));

        long strings = ResultSize.string("Cafe") + ResultSize.string("food");
        assertThat((long) two - one).as("the place, its strings and at most one more slot in the list")
                .isBetween(ResultSize.PLACE + strings, ResultSize.PLACE + strings + 8);
        assertThat(ResultSize.bytes(result(List.of(longName)))).isGreaterThan(one);
    }

    @Test
    void travelTimesFactsAndOpeningHoursAddTheirParts() {
        NearbyPlace plain = place("Cafe", null);
        NearbyPlace timed = plain.withTravel(new Leg(300L, 400L));
        NearbyPlace withFacts = new NearbyPlace(1, "Cafe", "food", null, 42.35, -71.06, 100, null, null, null, null,
                new PlaceFacts(true, true, "passed", null, OpenWeek.parse("{[480,1080),[1920,2520)}")));

        int base = ResultSize.bytes(result(List.of(plain)));

        assertThat(ResultSize.bytes(result(List.of(timed))) - base).isEqualTo(32);
        assertThat(ResultSize.bytes(result(List.of(withFacts)))).isGreaterThan(base + 100);
    }

    @Test
    void twoHundredPlacesWeighTensOfKilobytes() {
        NearbyPlace typical = new NearbyPlace(1, "Flour Bakery + Cafe", "food", "12 Farnsworth St, Boston", 42.35,
                -71.05, 900, 660L, 950L, null, null,
                new PlaceFacts(false, true, "passed", null, OpenWeek.parse("{[420,1200),[1860,2640),[3300,4080)}")));

        int bytes = ResultSize.bytes(result(Collections.nCopies(200, typical)));

        assertThat(bytes).isBetween(40_000, 120_000);
    }

    private static NearbyResult result(List<NearbyPlace> places) {
        return new NearbyResult(QUERY, "valhalla", null, places);
    }

    private static NearbyPlace place(String name, String address) {
        return new NearbyPlace(1, name, "food", address, 42.35, -71.06, 100, null, null);
    }
}
