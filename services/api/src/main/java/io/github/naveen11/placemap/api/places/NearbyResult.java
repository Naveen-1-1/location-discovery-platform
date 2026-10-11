package io.github.naveen11.placemap.api.places;

import java.util.List;

/**
 * A shared nearby result: up to {@link NearbyService#CANDIDATES} places, sorted by travel time when the engine
 * answered and by straight-line distance otherwise.
 *
 * @param travelTimeSource the routing engine's name, or {@code straight_line}
 * @param fallback why there are no travel times although a mode was asked for (timeout, breaker_open, …); null
 *     otherwise
 */
public record NearbyResult(NearbyQuery query, String travelTimeSource, String fallback, List<NearbyPlace> places) {}
