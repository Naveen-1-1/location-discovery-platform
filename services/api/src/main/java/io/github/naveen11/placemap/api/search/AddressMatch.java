package io.github.naveen11.placemap.api.search;

/** An address Photon found: a house, a street or a neighbourhood, to move the map to. */
public record AddressMatch(String label, double lat, double lon, String kind) {
}
