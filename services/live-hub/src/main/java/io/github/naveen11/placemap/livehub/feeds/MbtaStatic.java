package io.github.naveen11.placemap.livehub.feeds;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The few static GTFS facts the live MBTA feeds need to be readable, refreshed daily from the schedule zip (about
 * 25 MB, read as it streams in, never saved): route names, where each direction goes, and which station each platform
 * belongs to. The realtime feeds name only ids ("Red", direction 0, platform 70068); with this they become "Red Line
 * to Ashmont/Braintree" at Harvard.
 *
 * <p>Reference data, not live state: it changes a few times a month and is swapped in whole, so it lives in an
 * {@link AtomicReference} beside the snapshot writer rather than in it.
 */
public final class MbtaStatic implements PolledFeed {

    /** What the realtime parsers look up. Empty until the first download. */
    public record Names(Map<String, String> routes, Map<String, String> destinations, Map<String, String> parents) {

        static final Names EMPTY = new Names(Map.of(), Map.of(), Map.of());

        /** "Red Line", "Green Line B", "1"; the id itself if unknown. */
        public String route(String routeId) {
            return routes.getOrDefault(routeId, routeId);
        }

        /** "Alewife"; null if unknown. */
        public String destination(String routeId, int directionId) {
            return destinations.get(routeId + "/" + directionId);
        }

        /** The station a platform belongs to, or the stop itself. */
        public String station(String stopId) {
            return parents.getOrDefault(stopId, stopId);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MbtaStatic.class);
    private static final CSVFormat GTFS = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();
    private static final Duration DAILY = Duration.ofDays(1);
    private static final Duration RETRY = Duration.ofMinutes(10);

    private final RestClient http;
    private final String url;
    private final AtomicReference<Names> names = new AtomicReference<>(Names.EMPTY);

    public MbtaStatic(RestClient http, String url) {
        this.http = http;
        this.url = url;
    }

    public Names names() {
        return names.get();
    }

    @Override
    public String name() {
        return "mbta-gtfs";
    }

    @Override
    public Duration initialInterval() {
        return RETRY;
    }

    @Override
    public Duration poll() {
        try {
            Names loaded = http.get().uri(url).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IOException("GTFS zip answered " + response.getStatusCode());
                }
                return read(response.getBody());
            });
            names.set(loaded);
            log.info("mbta: loaded {} routes, {} directions, {} platforms", loaded.routes().size(),
                    loaded.destinations().size(), loaded.parents().size());
            return DAILY;
        } catch (RestClientException | IllegalStateException e) {
            log.warn("mbta: GTFS zip failed, retrying in {}: {}", RETRY, e.toString());
            return RETRY;
        }
    }

    /** Reads routes.txt, directions.txt and stops.txt from a GTFS zip stream; package-private for tests. */
    static Names read(InputStream zip) throws IOException {
        var routes = new HashMap<String, String>();
        var destinations = new HashMap<String, String>();
        var parents = new HashMap<String, String>();
        try (var in = new ZipInputStream(zip)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                switch (entry.getName()) {
                    case "routes.txt" -> each(in, r -> routes.put(r.get("route_id"), routeName(r)));
                    case "directions.txt" -> each(in, r -> destinations.put(r.get("route_id") + "/" + r.get("direction_id"),
                            r.get("direction_destination")));
                    case "stops.txt" -> each(in, r -> {
                        if (!r.get("parent_station").isBlank()) {
                            parents.put(r.get("stop_id"), r.get("parent_station"));
                        }
                    });
                    default -> {
                    }
                }
            }
        }
        if (routes.isEmpty()) {
            throw new IllegalStateException("GTFS zip has no routes.txt");
        }
        return new Names(Map.copyOf(routes), Map.copyOf(destinations), Map.copyOf(parents));
    }

    /** Buses go by number ("1", "SL1"); trains and ferries by name ("Red Line", "Green Line B"). */
    private static String routeName(CSVRecord route) {
        String shortName = route.get("route_short_name");
        String longName = route.get("route_long_name");
        boolean bus = "3".equals(route.get("route_type"));
        if (bus && !shortName.isBlank()) {
            return shortName;
        }
        return longName.isBlank() ? shortName : longName;
    }

    private interface Row {
        void accept(CSVRecord record);
    }

    /** Parses one zip entry without closing the zip stream. */
    private static void each(ZipInputStream in, Row row) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(new NonClosing(in), StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '﻿') { // GTFS files often start with a byte-order mark
            reader.reset();
        }
        for (CSVRecord record : GTFS.parse(reader)) {
            row.accept(record);
        }
    }

    /** Leaves the zip open when a CSV reader over one of its entries is done. */
    private static final class NonClosing extends java.io.FilterInputStream {
        NonClosing(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
        }
    }
}
