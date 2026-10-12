package io.github.naveen11.placemap.api.places;

import com.uber.h3core.H3Core;
import com.uber.h3core.LengthUnit;
import com.uber.h3core.util.LatLng;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;

/**
 * Snaps a search origin to the centre of its H3 resolution-10 cell. A cell covers about 15,000 m², so its
 * corners are about 70 m from the centre. Everyone searching from the same cell shares one result, and the exact
 * position goes no further than this class: it is never logged, cached or sent to the routing engine.
 */
@Component
public class OriginSnapper {

    static final int RESOLUTION = 10;

    private final H3Core h3;

    public OriginSnapper() {
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("could not load the H3 native library", e);
        }
    }

    public SnappedOrigin snap(double lat, double lon) {
        long cell = h3.latLngToCell(lat, lon, RESOLUTION);
        LatLng centre = h3.cellToLatLng(cell);
        // A cell is convex, so its farthest point from the centre is a corner.
        double farthest = h3.cellToBoundary(cell).stream()
                .mapToDouble(corner -> h3.greatCircleDistance(centre, corner, LengthUnit.m))
                .max()
                .orElseThrow();
        return new SnappedOrigin(h3.h3ToString(cell), centre.lat, centre.lng, (int) Math.ceil(farthest));
    }

    /** Great-circle distance in metres, for tests and checks. */
    double meters(double lat1, double lon1, double lat2, double lon2) {
        return h3.greatCircleDistance(new LatLng(lat1, lon1), new LatLng(lat2, lon2), LengthUnit.m);
    }
}
