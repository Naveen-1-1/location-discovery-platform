package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * EPSG:2249 (Massachusetts State Plane, US survey feet) against reference values from PROJ (computed with DuckDB's
 * spatial extension, 2026-10-11). The golden test against PostGIS itself is ReprojectionGoldenContainerTest.
 */
class ReprojectorTest {

    private final Reprojector reprojector = new Reprojector();

    @ParameterizedTest
    @CsvSource({
            "775000, 2955000, -71.060348995, 42.355830623",
            "757000, 2940000, -71.127185736, 42.314905301",
            "770500, 2965250, -71.076809346, 42.384019206",
            "742000, 2915000, -71.182988689, 42.246469817",
            "790000, 2970000, -71.004531892, 42.396765033"})
    void matchesProjWithinFiveCentimetres(double x, double y, double lon, double lat) {
        double[] wgs84 = reprojector.toWgs84("EPSG:2249", x, y);

        assertThat(metres(wgs84[0], wgs84[1], lon, lat)).isLessThan(0.05);
    }

    @Test
    void wgs84PassesThrough() {
        assertThat(reprojector.toWgs84("EPSG:4326", -71.06, 42.35)).containsExactly(-71.06, 42.35);
    }

    static double metres(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return 6_371_008.8 * Math.sqrt(dLat * dLat + dLon * dLon);
    }
}
