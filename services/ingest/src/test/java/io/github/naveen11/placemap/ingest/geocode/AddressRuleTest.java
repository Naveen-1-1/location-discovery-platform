package io.github.naveen11.placemap.ingest.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The query a record sends and the plan's acceptance rule, with Photon answers from 2026-10-11. */
class AddressRuleTest {

    private static final double LON = -71.1083678;
    private static final double LAT = 42.3752762;

    private static SourceRow pharmacy(String address, String postcode, String city) {
        return SourceRow.of("1568595346", "ATRIUS HEALTH", "pharmacy", null, null, address, postcode, null, null,
                city == null ? Map.of() : Map.of("city", city));
    }

    private static Candidate answer(String number, String street, String postcode, double lon, double lat) {
        return new Candidate(lon, lat, number, street, postcode, "Harvard Vanguard, " + number + " " + street);
    }

    @Test
    void theQueryIsTheAddressTownAndZipInLowerCase() {
        assertThat(AddressRule.query(pharmacy("1611 CAMBRIDGE ST", "021384302", "CAMBRIDGE")))
                .isEqualTo("1611 cambridge st, cambridge, ma 02138");
        assertThat(AddressRule.query(pharmacy("12  MAIN", "02108", "Boston/"))).isEqualTo("12 main, boston, ma 02108");
    }

    @Test
    void addressesThatCannotBePlacedAreNotSent() {
        assertThat(AddressRule.query(pharmacy("Logan Airport Terminal C", "02128", "East Boston"))).as("no number").isNull();
        assertThat(AddressRule.query(pharmacy("1611 CAMBRIDGE ST", null, "CAMBRIDGE"))).as("no ZIP").isNull();
        assertThat(AddressRule.query(pharmacy(null, "02138", "CAMBRIDGE"))).isNull();
    }

    @Test
    void anAnswerNeedsTheSameNumberStreetAndZipInsideTheArea() {
        SourceRow record = pharmacy("1611 CAMBRIDGE ST", "02138", "CAMBRIDGE");
        var area = TestData.area().checker();

        Geocode good = AddressRule.decide(record, List.of(
                answer("1613", "Cambridge Street", "02138", LON, LAT),       // next door
                answer("1611", "Cambridge Street", "02139", LON, LAT),       // other ZIP
                answer("1611", "Hampshire Street", "02138", LON, LAT),       // other street
                answer("1611", "Cambridge Street", "02138", -71.25, 42.37),  // Waltham, outside the area
                answer("1611", "Cambridge Street", "02138", LON, LAT)), area);
        assertThat(good).isEqualTo(Geocode.house(LON, LAT, "Harvard Vanguard, 1611 Cambridge Street"));

        assertThat(AddressRule.decide(record, List.of(answer("1613", "Cambridge Street", "02138", LON, LAT)), area))
                .isEqualTo(Geocode.none("no answer with the same number, street and postcode"));
        assertThat(AddressRule.decide(record, List.of(), area)).isEqualTo(Geocode.none("no answer"));
    }

    @Test
    void photonAnswersAreReadWithTheirLabels() {
        List<Candidate> candidates = PhotonGeocoder.candidates("""
                {"features": [
                 {"geometry": {"coordinates": [-71.1083678, 42.3752762], "type": "Point"}, "type": "Feature",
                  "properties": {"osm_type": "N", "type": "house", "housenumber": "1611", "street": "Cambridge Street",
                    "postcode": "02138", "city": "Cambridge", "name": "Harvard Vanguard", "osm_key": "amenity"}},
                 {"geometry": {"coordinates": [-71.0178109, 42.3671127], "type": "Point"}, "type": "Feature",
                  "properties": {"type": "street", "postcode": "02128", "city": "Boston", "name": "Logan Airport Terminal C"}}
                ], "type": "FeatureCollection"}
                """);

        assertThat(candidates).containsExactly(
                new Candidate(-71.1083678, 42.3752762, "1611", "Cambridge Street", "02138",
                        "Harvard Vanguard, 1611 Cambridge Street, Cambridge"),
                new Candidate(-71.0178109, 42.3671127, null, null, "02128", "Logan Airport Terminal C, Boston"));
    }
}
