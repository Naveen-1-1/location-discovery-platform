package io.github.naveen11.placemap.ingest.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SimilarityTest {

    @Test
    void namesIgnoreCaseAccentsPunctuationAndALeadingThe() {
        assertThat(Similarity.normalizeName("The Corner Café & Bar!")).isEqualTo("corner cafe and bar");
        assertThat(Similarity.normalizeName("Dunkin' Donuts")).isEqualTo("dunkin donuts");
        assertThat(Similarity.names(Similarity.normalizeName("Dunkin' Donuts"), Similarity.normalizeName("DUNKIN DONUTS")))
                .isEqualTo(1.0);
    }

    @Test
    void similarNamesScoreHighAndDifferentOnesLow() {
        double close = Similarity.names("harbor market", "harbour market");
        double far = Similarity.names("harbor market", "smile dental");

        assertThat(close).isGreaterThan(0.9);
        assertThat(far).isLessThan(0.6);
        assertThat(Similarity.names("", "anything")).isZero();
    }

    @Test
    void addressesCompareNumberAndStreetButNotTheStreetType() {
        var licence = Similarity.normalizeAddress("100 Federal");
        var overture = Similarity.normalizeAddress("100 Federal St");
        var osm = Similarity.normalizeAddress("100 Federal Street");

        assertThat(licence).isEqualTo(new Similarity.Address("100", "federal"));
        assertThat(Similarity.addresses(licence, overture)).isEqualTo(1.0);
        assertThat(Similarity.addresses(overture, osm)).isEqualTo(1.0);
    }

    @Test
    void aDifferentHouseNumberIsUsuallyADifferentBuilding() {
        double same = Similarity.addresses(Similarity.normalizeAddress("12 Main St"), Similarity.normalizeAddress("12 Main St"));
        double neighbour = Similarity.addresses(Similarity.normalizeAddress("12 Main St"), Similarity.normalizeAddress("14 Main St"));
        double unknownNumber = Similarity.addresses(Similarity.normalizeAddress("Main St"), Similarity.normalizeAddress("12 Main St"));

        assertThat(same).isEqualTo(1.0);
        assertThat(neighbour).isLessThan(0.35);
        assertThat(unknownNumber).isEqualTo(0.8);
    }

    @Test
    void missingAddressesAreNeutralAndRangesStartAtTheFirstNumber() {
        assertThat(Similarity.addresses(null, Similarity.normalizeAddress("1 Main St"))).isEqualTo(Similarity.UNKNOWN_ADDRESS);
        assertThat(Similarity.normalizeAddress("  ")).isNull();
        assertThat(Similarity.normalizeAddress("1-3 North Washington Street"))
                .isEqualTo(new Similarity.Address("1", "n washington"));
    }
}
