package io.github.naveen11.placemap.ingest.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;

/** The decision rules of ADR 0022 and gate G1 (ADR 0032) against a hand-built index around Boston Common. */
class MatcherTest {

    private static final SourcePriority PRIORITY = SourcePriority.load(TestData.REPO.resolve("data/source-priority.yaml"));
    private static final String OVERTURE = SourcePriority.OVERTURE;
    private static final String OSM = SourcePriority.OSM;
    private static final String LICENCES = "boston_active_food_licenses";

    private static final double LON = -71.0656;
    private static final double LAT = 42.3550;
    private static final double METRE = 1.0 / 111_320; // degrees of latitude per metre

    private static PlaceIndex.IndexedPlace place(long id, String name, String category, double northMetres, String address,
            String... sources) {
        return new PlaceIndex.IndexedPlace(id, category, LON, LAT + northMetres * METRE, Similarity.normalizeName(name),
                Similarity.normalizeAddress(address), Set.of(sources));
    }

    private static SourceRow record(String key, String name, String category, double northMetres, String address,
            Double confidence) {
        var attrs = new HashMap<String, String>();
        if (confidence != null) {
            attrs.put("confidence", confidence.toString());
        }
        return SourceRow.of(key, name, category, LON, LAT + northMetres * METRE, address, null, null, null, attrs);
    }

    private static Matcher matcher(String source, Map<String, Long> links, PlaceIndex.IndexedPlace... places) {
        return new Matcher(PlaceIndex.of(List.of(places), links), PRIORITY, source);
    }

    @Test
    void aRecordAlreadyLinkedKeepsItsPlace() {
        Matcher matcher = matcher(OVERTURE, Map.of("o1", 7L), place(1, "Corner Cafe", "food", 0, "12 Main St", OSM));

        assertThat(matcher.decide(record("o1", "Corner Cafe", "food", 0, "12 Main St", 0.95)))
                .isEqualTo(new MatchDecision.Keep(7));
    }

    @Test
    void theSamePlaceNearbyIsLinkedByScore() {
        Matcher matcher = matcher(OVERTURE, Map.of(), place(1, "Corner Cafe", "food", 0, "12 Main Street", OSM));

        MatchDecision decision = matcher.decide(record("o1", "Corner Café", "food", 20, "12 Main St", 0.95));

        assertThat(decision).isInstanceOfSatisfying(MatchDecision.Link.class, link -> {
            assertThat(link.placeId()).isEqualTo(1);
            assertThat(link.method()).isEqualTo("score");
            assertThat(link.score()).isGreaterThanOrEqualTo(Matcher.LINK_THRESHOLD);
        });
    }

    @Test
    void theBestScoringCandidateWinsNotTheNearest() {
        Matcher matcher = matcher(OSM, Map.of(),
                place(1, "Smile Dental", "food", 5, "14 Main St", OVERTURE),
                place(2, "Corner Cafe", "food", 40, "12 Main St", OVERTURE));

        MatchDecision decision = matcher.decide(record("n1", "Corner Cafe", "food", 0, "12 Main Street", null));

        assertThat(decision).isInstanceOfSatisfying(MatchDecision.Link.class, link -> assertThat(link.placeId()).isEqualTo(2));
    }

    @Test
    void placesBeyondOneHundredMetresAreNotCandidates() {
        Matcher matcher = matcher(OSM, Map.of(), place(1, "Corner Cafe", "food", 120, "12 Main St", OVERTURE));

        assertThat(matcher.decide(record("n1", "Corner Cafe", "food", 0, "12 Main St", null)))
                .isEqualTo(new MatchDecision.Create());
    }

    @Test
    void aBorderlineMatchGoesToReviewAndCreatesNothing() {
        // Similar name, other side of the block, no address on either side: 0.60–0.80.
        Matcher matcher = matcher(OSM, Map.of(), place(1, "Harbor Cafe", "food", 70, null, OVERTURE));

        MatchDecision decision = matcher.decide(record("n1", "Harbour Coffee", "food", 0, null, null));

        assertThat(decision).isInstanceOfSatisfying(MatchDecision.Review.class, review -> {
            assertThat(review.placeId()).isEqualTo(1);
            assertThat(review.score()).isBetween(Matcher.REVIEW_THRESHOLD, Matcher.LINK_THRESHOLD);
        });
    }

    @Test
    void confidentOverturePlacesMayStandAloneButDoubtfulOnesMayNot() {
        Matcher matcher = matcher(OVERTURE, Map.of());

        assertThat(matcher.decide(record("o1", "New Bakery", "food", 0, null, 0.95))).isEqualTo(new MatchDecision.Create());
        assertThat(matcher.decide(record("o2", "Quiet Diner", "food", 0, null, 0.60)))
                .isEqualTo(new MatchDecision.Unlinked("awaiting corroboration"));
    }

    @Test
    void aDoubtfulOverturePlaceJoinsANearbyPlaceThatAnotherSourceBacks() {
        // Far enough and without addresses, so the score alone stays under 0.80; the name is close enough (≥ 0.85).
        Matcher matcher = matcher(OVERTURE, Map.of(), place(1, "Harbor Market", "grocery", 90, null, OSM));

        MatchDecision decision = matcher.decide(record("o1", "Harbour Market", "grocery", 0, null, 0.70));

        assertThat(decision).isInstanceOfSatisfying(MatchDecision.Link.class, link -> {
            assertThat(link.placeId()).isEqualTo(1);
            assertThat(link.method()).isEqualTo("corroborated");
        });
    }

    @Test
    void corroborationNeedsAnotherSourceNotJustAnotherOverturePlace() {
        Matcher matcher = matcher(OVERTURE, Map.of(), place(1, "Harbor Market", "grocery", 90, null, OVERTURE));

        assertThat(matcher.decide(record("o1", "Harbour Market", "grocery", 0, null, 0.70)))
                .isNotInstanceOf(MatchDecision.Link.class);
    }

    @Test
    void overturePharmaciesAlwaysNeedCorroborationEvenWhenConfident() {
        Matcher matcher = matcher(OVERTURE, Map.of());

        assertThat(matcher.decide(record("o1", "Sanofi Research", "pharmacy", 0, null, 0.99)))
                .isEqualTo(new MatchDecision.Unlinked("awaiting corroboration"));
    }

    @Test
    void enrichmentSourcesLinkButNeverCreate() {
        Matcher withPlace = matcher(LICENCES, Map.of(), place(1, "Corner Cafe", "food", 10, "12 Main St", OSM, OVERTURE));
        Matcher empty = matcher(LICENCES, Map.of());

        assertThat(withPlace.decide(record("L1", "Corner Cafe", "food", 0, "12 Main", null)))
                .isInstanceOf(MatchDecision.Link.class);
        assertThat(empty.decide(record("L1", "Corner Cafe", "food", 0, "12 Main", null)))
                .isEqualTo(new MatchDecision.Unlinked("this source does not create places in this category"));
    }

    @Test
    void recordsWithoutALocationStayUnlinked() {
        SourceRow noLocation = SourceRow.of("L1", "Tall Ship", "food", null, null, null, null, null, null, Map.of());

        assertThat(matcher(LICENCES, Map.of()).decide(noLocation)).isEqualTo(new MatchDecision.Unlinked("no location"));
    }

    @Test
    void priorityRanksExistenceSourcesBeforeEnrichmentOnes() {
        List<SourcePriority.Role> food = PRIORITY.roles().stream().filter(r -> r.category().equals("food")).toList();

        assertThat(food.getFirst()).isEqualTo(new SourcePriority.Role("food", OVERTURE, 0, true));
        assertThat(food.get(1)).isEqualTo(new SourcePriority.Role("food", OSM, 1, true));
        assertThat(food).filteredOn(r -> r.source().equals(LICENCES))
                .singleElement().satisfies(r -> assertThat(r.existence()).isFalse());
    }

    private static final String OPEN_SPACE = "boston_open_space";
    private static final double LON_METRE = METRE / Math.cos(Math.toRadians(LAT));

    /** A park outline from 0 to 800 m north of the test point and 300 m either side of it, as WKT. */
    private static String parkOutline() {
        double w = LON - 300 * LON_METRE;
        double e = LON + 300 * LON_METRE;
        double s = LAT;
        double n = LAT + 800 * METRE;
        return "MULTIPOLYGON (((%s %s, %s %s, %s %s, %s %s, %s %s)))".formatted(w, s, e, s, e, n, w, n, w, s);
    }

    @Test
    void aParkRecordIsComparedWithPlacesInsideItsOutlineNotJustNearItsPoint() {
        // OSM's point for the park is 600 m from the city's: far beyond 100 m, but inside the city's outline.
        Matcher matcher = matcher(OPEN_SPACE, Map.of(), place(1, "Franklin Park", "park", 600, null, OSM));
        SourceRow city = SourceRow.of("1001", "Franklin Park", "park", LON, LAT, null, null, null, null, Map.of());

        assertThat(matcher.decide(city)).as("by point").isEqualTo(new MatchDecision.Create());
        assertThat(matcher.decide(city.withOutline(parkOutline()))).isInstanceOfSatisfying(MatchDecision.Link.class,
                link -> {
                    assertThat(link.placeId()).isEqualTo(1);
                    assertThat(link.score()).isEqualTo(0.875); // name 1, address unknown 0.5, inside: distance 1
                });
    }

    @Test
    void aRecordNearAnOutlinedPlaceIsComparedWithTheOutline() {
        // The city's park is the place (its point at the south edge); OSM's record for it lies 600 m north, inside.
        var outline = PlaceIndex.read(new WKTReader(), parkOutline());
        var park = new PlaceIndex.IndexedPlace(1, "park", LON, LAT, Similarity.normalizeName("Franklin Park"), null,
                Set.of(OPEN_SPACE), outline);
        SourceRow osm = record("way/1", "Franklin Park", "park", 600, null, null);

        assertThat(matcher(OSM, Map.of(), park).decide(osm)).isInstanceOfSatisfying(MatchDecision.Link.class,
                link -> assertThat(link.placeId()).isEqualTo(1));
        var withoutOutline = new PlaceIndex.IndexedPlace(1, "park", LON, LAT, park.normalizedName(), null,
                Set.of(OPEN_SPACE));
        assertThat(matcher(OSM, Map.of(), withoutOutline).decide(osm)).isEqualTo(new MatchDecision.Create());
    }

    @Test
    void outlineDistancesAreMeasuredFromTheEdge() {
        var outline = PlaceIndex.read(new WKTReader(), parkOutline());
        var index = PlaceIndex.of(List.of(new PlaceIndex.IndexedPlace(1, "park", LON, LAT, "park", null, Set.of(OSM),
                outline)), Map.of());

        assertThat(PlaceIndex.metersToOutline(outline, LON, LAT + 400 * METRE)).isZero();
        assertThat(PlaceIndex.metersToOutline(outline, LON, LAT - 50 * METRE)).isCloseTo(50, offset(0.5));
        assertThat(index.near(LON + 340 * LON_METRE, LAT + 700 * METRE, 100)).singleElement()
                .satisfies(n -> assertThat(n.meters()).isCloseTo(40, offset(0.5)));
        assertThat(index.near(LON + 420 * LON_METRE, LAT + 700 * METRE, 100)).isEmpty();
    }
}
