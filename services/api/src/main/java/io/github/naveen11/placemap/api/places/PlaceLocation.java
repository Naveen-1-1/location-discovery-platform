package io.github.naveen11.placemap.api.places;

/** Where a live place is, for routing to it. */
public record PlaceLocation(long id, String name, double lat, double lon) {}
