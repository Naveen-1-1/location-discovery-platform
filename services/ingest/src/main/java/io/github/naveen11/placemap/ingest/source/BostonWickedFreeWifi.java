package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Boston's free public Wi-Fi ("Wicked Free WiFi", 299 access points on 2026-10-11), the only source of the Free
 * Wi-Fi category. People look for a place with Wi-Fi, not an access point, so the fetch groups a network's access
 * points that stand within 75 m of each other into one site: the three in a community centre become one place, and a
 * neighbourhood network along a main street becomes a few. A site is keyed by its network and its lowest device serial,
 * and placed at the middle of its access points.
 *
 * <p>Access points without coordinates (mobile kits, about one in six) become sites without a location and are
 * skipped at parse, so they are counted but never shown.
 */
@Component
public class BostonWickedFreeWifi extends CkanCsvSource {

    public static final String ID = "boston_wicked_free_wifi";

    static final double SITE_RADIUS_METERS = 75;

    private static final List<String> DOWNLOAD = List.of(
            "device_serial", "neighborhood_name", "device_address", "device_lat", "device_long", "inside_outside");
    private static final List<String> COLUMNS = List.of(
            "site", "network", "lon", "lat", "address", "access_points", "inside_outside");
    private static final CSVFormat READ = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();

    private record AccessPoint(String serial, String address, Double lon, Double lat, String insideOutside) {
    }

    @Override
    public String sourceId() {
        return ID;
    }

    @Override
    public List<String> columns() {
        return COLUMNS;
    }

    @Override
    protected List<String> sortColumns() {
        return List.of("device_serial");
    }

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        Path devices = target.resolveSibling("wifi_devices.csv");
        try {
            download(source, http, DOWNLOAD, devices);
            writeSnapshot(devices, target);
        } finally {
            Files.deleteIfExists(devices);
        }
    }

    void writeSnapshot(Path devices, Path target) throws IOException {
        var byNetwork = new TreeMap<String, List<AccessPoint>>();
        try (Reader reader = Files.newBufferedReader(devices, StandardCharsets.UTF_8);
                CSVParser parser = CSVParser.parse(reader, READ)) {
            for (CSVRecord device : parser) {
                String serial = blankToNull(device.get("device_serial"));
                if (serial == null) {
                    continue;
                }
                String network = blankToNull(device.get("neighborhood_name"));
                byNetwork.computeIfAbsent(network == null ? "Boston" : network, k -> new ArrayList<>())
                        .add(new AccessPoint(serial, blankToNull(device.get("device_address")),
                                number(device.get("device_long")), number(device.get("device_lat")),
                                blankToNull(device.get("inside_outside"))));
            }
        }
        var rows = new ArrayList<List<String>>();
        for (Map.Entry<String, List<AccessPoint>> network : byNetwork.entrySet()) {
            for (List<AccessPoint> site : sites(network.getValue())) {
                rows.add(row(network.getKey(), site));
            }
        }
        SnapshotFiles.writeSorted(target, COLUMNS, rows);
    }

    /** Access points within 75 m of another in the same site (single linkage); those without a location alone. */
    static List<List<AccessPoint>> sites(List<AccessPoint> points) {
        var sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparing(AccessPoint::serial));
        int[] parent = new int[sorted.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                AccessPoint a = sorted.get(i);
                AccessPoint b = sorted.get(j);
                if (a.lon() != null && b.lon() != null && meters(a, b) <= SITE_RADIUS_METERS) {
                    parent[root(parent, j)] = root(parent, i);
                }
            }
        }
        var sites = new LinkedHashMap<Integer, List<AccessPoint>>();
        for (int i = 0; i < sorted.size(); i++) {
            sites.computeIfAbsent(root(parent, i), k -> new ArrayList<>()).add(sorted.get(i));
        }
        return List.copyOf(sites.values());
    }

    private static List<String> row(String network, List<AccessPoint> site) {
        List<AccessPoint> placed = site.stream().filter(p -> p.lon() != null).toList();
        String lon = placed.isEmpty() ? "" : Double.toString(placed.stream().mapToDouble(AccessPoint::lon).average().orElseThrow());
        String lat = placed.isEmpty() ? "" : Double.toString(placed.stream().mapToDouble(AccessPoint::lat).average().orElseThrow());
        // The most common address among the site's access points; ties go to the first alphabetically.
        String address = site.stream().map(AccessPoint::address).filter(a -> a != null)
                .collect(Collectors.groupingBy(a -> a, TreeMap::new, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey).orElse("");
        List<String> sides = site.stream().map(AccessPoint::insideOutside).filter(s -> s != null).distinct().toList();
        return List.of(network + "|" + site.getFirst().serial(), network, lon, lat, address,
                Integer.toString(site.size()), sides.size() == 1 ? sides.getFirst() : "");
    }

    @Override
    public String key(CsvRow row) {
        return row.get("site");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing site");
        }
        if (row.get("lon") == null) {
            return RowResult.skipped("no location (mobile or unplaced access point)");
        }
        var point = context.readPoint(row.get("lon"), row.get("lat"));
        if (point.failure() != null) {
            return point.failure();
        }
        String network = row.get("network");
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        attrs.put("network", network);
        attrs.put("access_points", row.get("access_points"));
        attrs.put("inside_outside", row.get("inside_outside"));
        return RowResult.accepted(SourceRow.of(key, siteName(network), category, point.lon(), point.lat(),
                streetPart(row.get("address")), null, null, null, attrs));
    }

    /** "BCYF-Vine-CC_ Indoor" → "Wicked Free WiFi · BCYF Vine CC". */
    static String siteName(String network) {
        String clean = network == null ? "" : network.replaceAll("[-_]+", " ")
                .replaceAll("(?i)\\b(indoor|internal|inside|outdoor|outside)\\b", " ")
                .replaceAll("\\s+", " ").strip();
        return "Wicked Free WiFi · " + (clean.isEmpty() ? "Boston" : clean);
    }

    /** "1483 Tremont St Boston, MA" → "1483 Tremont St Boston"; the city and state add nothing within Boston. */
    private static String streetPart(String address) {
        if (address == null) {
            return null;
        }
        int comma = address.indexOf(',');
        return (comma < 0 ? address : address.substring(0, comma)).strip();
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static double meters(AccessPoint a, AccessPoint b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6_371_008.8 * Math.asin(Math.sqrt(h));
    }

    private static Double number(String text) {
        String value = blankToNull(text);
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
