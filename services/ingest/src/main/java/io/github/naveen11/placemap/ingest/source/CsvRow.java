package io.github.naveen11.placemap.ingest.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVRecord;

/** One row of a saved snapshot. Values are trimmed; blank values read as {@code null}. */
public final class CsvRow {

    private final List<String> columns;
    private final CSVRecord record;

    public CsvRow(List<String> columns, CSVRecord record) {
        this.columns = columns;
        this.record = record;
    }

    public String get(String column) {
        String value = record.get(column).strip();
        return value.isEmpty() ? null : value;
    }

    /** The raw values in column order, for the batch checksum. */
    public List<String> values() {
        return record.toList();
    }

    /** The row as column → value, for the reject table. Snapshots never contain personal fields (ADR 0031). */
    public Map<String, String> asMap() {
        var map = new LinkedHashMap<String, String>();
        for (String column : columns) {
            map.put(column, get(column));
        }
        return map;
    }
}
