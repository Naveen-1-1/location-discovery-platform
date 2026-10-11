package io.github.naveen11.placemap.ingest.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A normalised record ready for {@code place.source_record}. {@code rowHash} covers every stored column, so an
 * unchanged record can be skipped on re-import without being rewritten (ADR 0020).
 *
 * @param lon WGS84 longitude, or null with {@code lat} when the source gives no location
 * @param attrs source-specific fields, sorted so the hash does not depend on insertion order
 * @param outline the place's outline as WGS84 MULTIPOLYGON WKT (a park), or null; matching then compares other
 *     records with the outline instead of the point
 */
public record SourceRow(
        String key,
        String name,
        String category,
        Double lon,
        Double lat,
        String address,
        String postcode,
        String phone,
        String website,
        SortedMap<String, String> attrs,
        String rowHash,
        String outline) {

    public SourceRow {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        if ((lon == null) != (lat == null)) {
            throw new IllegalArgumentException("lon and lat must both be set or both be null");
        }
        attrs = Collections.unmodifiableSortedMap(new TreeMap<>(attrs));
    }

    public static SourceRow of(String key, String name, String category, Double lon, Double lat, String address,
            String postcode, String phone, String website, Map<String, String> attrs) {
        var sorted = new TreeMap<String, String>();
        attrs.forEach((k, v) -> {
            if (v != null) {
                sorted.put(k, v);
            }
        });
        String hash = hash(name, category, lon, lat, address, postcode, phone, website, sorted, null);
        return new SourceRow(key, name, category, lon, lat, address, postcode, phone, website, sorted, hash, null);
    }

    /** This record with an outline; the hash then covers it too. */
    public SourceRow withOutline(String outlineWkt) {
        String hash = hash(name, category, lon, lat, address, postcode, phone, website, attrs, outlineWkt);
        return new SourceRow(key, name, category, lon, lat, address, postcode, phone, website, attrs, hash, outlineWkt);
    }

    /** EWKT for PostGIS, or null without a location. */
    public String ewkt() {
        return lon == null ? null : "SRID=4326;POINT(" + lon + " " + lat + ")";
    }

    /** EWKT of the outline for PostGIS, or null. */
    public String outlineEwkt() {
        return outline == null ? null : "SRID=4326;" + outline;
    }

    private static String hash(String name, String category, Double lon, Double lat, String address, String postcode,
            String phone, String website, SortedMap<String, String> attrs, String outline) {
        var text = new StringBuilder();
        for (Object field : new Object[] {name, category, lon, lat, address, postcode, phone, website}) {
            text.append(field == null ? "\u0000" : field.toString()).append('\u001f');
        }
        attrs.forEach((k, v) -> text.append(k).append('=').append(v).append('\u001f'));
        if (outline != null) { // only when set, so records without one keep the hashes they were stored with
            text.append("outline=").append(outline).append('\u001f');
        }
        return sha256Hex(text.toString());
    }

    static String sha256Hex(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }
}
