package io.github.naveen11.placemap.api.search;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.search.*}: the search box (ADR 0008, 0042). Addresses come from Photon through this api, never
 * straight from the browser, so users' keystrokes and addresses stay with us (owner decision).
 *
 * @param photonUrl Photon's search endpoint: the public instance in development (ADR 0033), our own later
 * @param photonTimeout how long one Photon call may take; the search answers with places only after that
 * @param photonConcurrency the Photon bulkhead: calls in flight at once; one more is answered without addresses
 * @param cacheTtl how long a Photon answer is reused for the same text near the same place
 * @param deadline the whole search
 * @param bbox the four towns' bounding box (data/area/area.geojson), "minLon,minLat,maxLon,maxLat"
 */
@ConfigurationProperties("placemap.search")
public record SearchProperties(
        @DefaultValue("https://photon.komoot.io/api/") URI photonUrl,
        @DefaultValue("1200ms") Duration photonTimeout,
        @DefaultValue("4") int photonConcurrency,
        @DefaultValue("10000") int cacheSize,
        @DefaultValue("10m") Duration cacheTtl,
        @DefaultValue("1500ms") Duration deadline,
        @DefaultValue("8") int maxPlaces,
        @DefaultValue("5") int maxAddresses,
        @DefaultValue("-71.1913,42.2279,-70.8688,42.4182") String bbox) {}
