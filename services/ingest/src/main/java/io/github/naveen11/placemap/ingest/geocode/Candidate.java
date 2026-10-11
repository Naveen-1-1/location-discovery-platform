package io.github.naveen11.placemap.ingest.geocode;

/** One answer from the geocoding service, before our acceptance rule. Fields the service did not give are null. */
public record Candidate(double lon, double lat, String houseNumber, String street, String postcode, String label) {
}
