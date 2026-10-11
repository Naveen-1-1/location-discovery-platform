package io.github.naveen11.placemap.ingest.snapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

/** Opens snapshot files: RFC 4180 CSV with a header row, UTF-8, optional byte-order mark. */
final class SnapshotCsv {

    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get();

    private SnapshotCsv() {
    }

    static CSVParser open(Path file) throws IOException {
        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        reader.mark(1);
        if (reader.read() != '﻿') {
            reader.reset();
        }
        return CSVParser.parse(reader, FORMAT);
    }

    /** Fails unless the header is exactly the expected columns, so schema drift (or an ignored column filter) stops the run. */
    static void checkHeader(CSVParser parser, List<String> expected, Path file) {
        List<String> actual = parser.getHeaderNames();
        if (!actual.equals(expected)) {
            throw new SnapshotException("snapshot " + file + " has columns " + actual + ", expected " + expected);
        }
    }
}
