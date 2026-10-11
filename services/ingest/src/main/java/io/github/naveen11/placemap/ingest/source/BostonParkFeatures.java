package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * What Boston's parks have: playgrounds, courts, spray decks, restrooms, dog parks (1,788 assets in 216 parks on
 * 2026-10-11). The assets have no ids of their own, so the fetch turns them into one record per park, keyed by the
 * open-space parcel's {@code OS_ID}, listing each kind of asset with its count ("Basketball:2;Playground:1"). Those
 * records join the parcel's place by that key (ADR 0022's ID join) and are never matched by name or distance.
 */
@Component
public class BostonParkFeatures extends CkanCsvSource {

    public static final String ID = "boston_park_features";

    private static final List<String> DOWNLOAD = List.of("OS_ID", "Park_Name", "Asset");
    private static final List<String> COLUMNS = List.of("OS_ID", "Park_Name", "features", "assets");
    private static final CSVFormat READ = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();

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
        return DOWNLOAD;
    }

    @Override
    public Optional<String> joinsSource() {
        return Optional.of(BostonOpenSpace.ID);
    }

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        Path assets = target.resolveSibling("park_features.csv");
        try {
            download(source, http, DOWNLOAD, assets);
            writeSnapshot(assets, target);
        } finally {
            Files.deleteIfExists(assets);
        }
    }

    void writeSnapshot(Path assets, Path target) throws IOException {
        var names = new TreeMap<String, String>();
        var counts = new TreeMap<String, TreeMap<String, Integer>>();
        try (Reader reader = Files.newBufferedReader(assets, StandardCharsets.UTF_8);
                CSVParser parser = CSVParser.parse(reader, READ)) {
            for (CSVRecord asset : parser) {
                String park = asset.get("OS_ID").strip();
                String kind = asset.get("Asset").strip();
                if (park.isEmpty() || kind.isEmpty()) {
                    continue;
                }
                String name = asset.get("Park_Name").strip();
                if (!name.isEmpty()) {
                    names.putIfAbsent(park, name);
                }
                counts.computeIfAbsent(park, k -> new TreeMap<>()).merge(kind, 1, Integer::sum);
            }
        }
        var rows = new ArrayList<List<String>>();
        for (Map.Entry<String, TreeMap<String, Integer>> park : counts.entrySet()) {
            String features = park.getValue().entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining(";"));
            int total = park.getValue().values().stream().mapToInt(Integer::intValue).sum();
            rows.add(List.of(park.getKey(), names.getOrDefault(park.getKey(), ""), features, Integer.toString(total)));
        }
        SnapshotFiles.writeSorted(target, COLUMNS, rows);
    }

    @Override
    public String key(CsvRow row) {
        return row.get("OS_ID");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing OS_ID");
        }
        String features = row.get("features");
        if (features == null) {
            return RowResult.rejected("no features");
        }
        String name = Optional.ofNullable(row.get("Park_Name")).orElse("Park " + key);
        return RowResult.accepted(SourceRow.of(key, name, null, null, null, null, null, null, null,
                Map.of("features", features, "assets", Optional.ofNullable(row.get("assets")).orElse("0"))));
    }
}
