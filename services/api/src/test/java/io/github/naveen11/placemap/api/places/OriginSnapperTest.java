package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.api.TestH3;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Snapping: the error is never more than the cell's radius, and the same cell always gives one answer. */
class OriginSnapperTest {

    private final OriginSnapper snapper = new OriginSnapper(TestH3.CORE);

    @Test
    void theSnappingErrorIsNeverMoreThanTheReportedMaximum() {
        // 10,000 random points over Boston, Cambridge, Somerville and Brookline; a fixed seed keeps failures
        // reproducible.
        var random = new Random(20261011);
        int largest = 0;
        for (int i = 0; i < 10_000; i++) {
            double lat = 42.29 + random.nextDouble() * 0.13;
            double lon = -71.18 + random.nextDouble() * 0.18;

            SnappedOrigin origin = snapper.snap(lat, lon);

            double error = snapper.meters(lat, lon, origin.lat(), origin.lon());
            assertThat(error).as("point %f,%f in cell %s", lat, lon, origin.cell())
                    .isLessThanOrEqualTo(origin.maxErrorMeters());
            largest = Math.max(largest, origin.maxErrorMeters());
        }
        // Resolution 10 cells: corners about 66–76 m from the centre at Boston's latitude. The UI says "approx. 70 m".
        assertThat(largest).isBetween(60, 85);
    }

    @Test
    void pointsInOneCellShareTheirOriginAndPointsFarApartDoNot() {
        SnappedOrigin a = snapper.snap(42.355000, -71.065600);
        SnappedOrigin b = snapper.snap(42.355001, -71.065601);
        SnappedOrigin far = snapper.snap(42.373600, -71.119000);

        assertThat(b).isEqualTo(a);
        assertThat(far.cell()).isNotEqualTo(a.cell());
        assertThat(a.cell()).hasSize(15).startsWith("8a");
    }
}
