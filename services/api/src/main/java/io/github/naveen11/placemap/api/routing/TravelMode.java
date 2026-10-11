package io.github.naveen11.placemap.api.routing;

import java.util.Locale;

/** How the user travels. The API takes the lower-case name, as in {@code mode=walk}. */
public enum TravelMode {
    WALK,
    BIKE,
    DRIVE;

    public String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException for anything but walk, bike or drive */
    public static TravelMode fromParam(String value) {
        for (TravelMode mode : values()) {
            if (mode.param().equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown mode: " + value + " (use walk, bike or drive)");
    }
}
