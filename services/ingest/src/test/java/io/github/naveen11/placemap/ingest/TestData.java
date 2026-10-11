package io.github.naveen11.placemap.ingest;

import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.source.CsvRow;
import io.github.naveen11.placemap.ingest.source.ParseContext;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

/** The real registry files from the repository, plus the fixture snapshot (40 real rows, 2026-10-10). */
public final class TestData {

    public static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    public static final Path BOSTON_LICENCES =
            Path.of("src/test/resources/fixtures/boston_active_food_licenses.csv").toAbsolutePath();

    private static final SourceCatalog CATALOG = SourceCatalog.load(REPO.resolve("data/sources.yaml"));
    private static final CategoryRules CATEGORIES = CategoryRules.load(REPO.resolve("data/categories.yaml"));
    private static final CoverageArea AREA = CoverageArea.load(REPO.resolve("data/area/area.geojson"));

    private TestData() {
    }

    public static SourceCatalog catalog() {
        return CATALOG;
    }

    public static CategoryRules categories() {
        return CATEGORIES;
    }

    public static CoverageArea area() {
        return AREA;
    }

    public static ParseContext parseContext() {
        return new ParseContext(CATEGORIES, AREA.checker());
    }

    /** Rows of a CSV file with a header. */
    public static List<CsvRow> rows(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return parse(reader);
        }
    }

    /** Rows of inline CSV text with a header. */
    public static List<CsvRow> rows(String csv) throws IOException {
        return parse(new StringReader(csv));
    }

    private static List<CsvRow> parse(Reader reader) throws IOException {
        try (var parser = CSVParser.parse(reader, CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get())) {
            List<String> columns = parser.getHeaderNames();
            return parser.getRecords().stream().map(r -> new CsvRow(columns, r)).toList();
        }
    }
}
