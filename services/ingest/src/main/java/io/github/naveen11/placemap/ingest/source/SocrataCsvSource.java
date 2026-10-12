package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A City of Cambridge dataset from its Socrata API. Asks for only {@link #columns()} ({@code $select}), so personal
 * fields are never downloaded, sorted by a unique key ({@code $order}) so pages are stable and unchanged data
 * gives a byte-identical snapshot. Pages of 50,000 rows are joined into one CSV file.
 */
public abstract class SocrataCsvSource implements SourceAdapter {

    static final int PAGE = 50_000;
    private static final JsonMapper JSON = new JsonMapper();
    private static final Pattern POSTCODE = Pattern.compile("\\b(\\d{5})(-\\d{4})?\\s*$");
    private static final CSVFormat READ = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();

    /** Columns that order rows uniquely. */
    protected abstract List<String> orderColumns();

    /** Rows per request; Socrata serves at most 50,000. */
    protected int pageSize() {
        return PAGE;
    }

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        if (!"socrata".equals(source.access().method())) {
            throw new IllegalStateException(sourceId() + " is registered as " + source.access().method() + ", not socrata");
        }
        String csv = source.access().url().replaceFirst("\\.json$", ".csv");
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Path page = target.resolveSibling(target.getFileName() + ".page");
        try (Writer out = Files.newBufferedWriter(part, StandardCharsets.UTF_8);
                var printer = new CSVPrinter(out, CSVFormat.RFC4180.builder().setHeader(columns().toArray(String[]::new)).get())) {
            for (int offset = 0; ; offset += pageSize()) {
                http.download(URI.create(csv + "?$select=" + encode(String.join(",", columns()))
                        + "&$order=" + encode(String.join(",", orderColumns()))
                        + "&$limit=" + pageSize() + "&$offset=" + offset), page);
                int rows = 0;
                try (Reader reader = Files.newBufferedReader(page, StandardCharsets.UTF_8);
                        CSVParser parser = CSVParser.parse(reader, READ)) {
                    if (!parser.getHeaderNames().equals(columns())) {
                        throw new IOException(sourceId() + ": Socrata returned columns " + parser.getHeaderNames());
                    }
                    for (CSVRecord record : parser) {
                        printer.printRecord(record.toList());
                        rows++;
                    }
                }
                if (rows < pageSize()) {
                    break;
                }
            }
        } finally {
            Files.deleteIfExists(page);
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** When the dataset's rows or definition last changed, from Socrata's view metadata. */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        URI resource = URI.create(source.access().url());
        String view = Path.of(resource.getPath()).getFileName().toString().replaceFirst("\\.json$", "");
        JsonNode metadata = JSON.readTree(http.getText(
                URI.create(resource.getScheme() + "://" + resource.getHost() + "/api/views/" + view + ".json")));
        return "socrata:" + metadata.path("rowsUpdatedAt").asString("") + ";" + metadata.path("viewLastModified").asString("");
    }

    /** "2310 Massachusetts Ave, Cambridge, MA 02140" → the street part. */
    static String street(String fullAddress) {
        if (fullAddress == null) {
            return null;
        }
        int comma = fullAddress.indexOf(',');
        return (comma < 0 ? fullAddress : fullAddress.substring(0, comma)).strip();
    }

    /** "…, MA 02140" → "02140". */
    static String postcode(String fullAddress) {
        if (fullAddress == null) {
            return null;
        }
        var match = POSTCODE.matcher(fullAddress);
        return match.find() ? match.group(1) : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
