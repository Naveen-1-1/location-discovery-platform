package io.github.naveen11.placemap.ingest.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.ingest.TestData;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The real data/sources.yaml, data/categories.yaml and area polygons load and mean what the importer assumes. */
class CatalogTest {

    @Test
    void readsTheRegistryEntryOfTheBostonLicences() {
        SourceDefinition source = TestData.catalog().get("boston_active_food_licenses");

        assertThat(source.isEnabled()).isTrue();
        assertThat(source.access().method()).isEqualTo("ckan_csv");
        assertThat(source.access().url()).startsWith("https://data.boston.gov/datastore/dump/");
        assertThat(source.dropFields()).containsExactly("dayphn_cleaned");
        assertThat(source.refreshInterval()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void knowsDisabledSourcesAndRejectsUnknownOnes() {
        assertThat(TestData.catalog().get("ma_wic_vendors").isEnabled()).isFalse();
        assertThatThrownBy(() -> TestData.catalog().get("no_such_source")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsOfficialClassificationsOntoAppCategories() {
        CategoryRules rules = TestData.categories();

        assertThat(rules.officialCategory("boston_active_food_licenses", Map.of("descript", "Eating & Drinking")::get))
                .contains("food");
        assertThat(rules.officialCategory("boston_active_food_licenses", Map.of("descript", "Retail Food")::get))
                .isEmpty();
        // Numbers in YAML match text in rows.
        assertThat(rules.officialCategory("mbta_gtfs", Map.of("location_type", "1")::get)).contains("transit");
        assertThat(rules.officialCategory("fdic_bank_locations", Map.of("SERVTYPE", "11")::get)).contains("bank");
        assertThat(rules.officialCategory("fdic_bank_locations", Map.of("SERVTYPE", "13")::get)).as("home banking")
                .isEmpty();
        // An empty rule matches every row of that source.
        assertThat(rules.officialCategory("massgis_hospitals", Map.<String, String>of()::get)).contains("hospital");
    }

    @Test
    void coverageAreaHoldsTheFourTownsOnly() {
        CoverageArea.Checker area = TestData.area().checker();

        assertThat(area.covers(-71.0656, 42.3550)).as("Boston Common").isTrue();
        assertThat(area.covers(-71.1056, 42.3736)).as("Cambridge City Hall").isTrue();
        assertThat(area.covers(-71.0995, 42.3876)).as("Somerville City Hall").isTrue();
        assertThat(area.covers(-71.1212, 42.3329)).as("Brookline Town Hall").isTrue();
        assertThat(area.covers(-71.2092, 42.3370)).as("Newton City Hall").isFalse();
    }
}
