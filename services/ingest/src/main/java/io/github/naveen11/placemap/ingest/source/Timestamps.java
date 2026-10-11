package io.github.naveen11.placemap.ingest.source;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;

/**
 * The publishers' date formats. Boston writes "2018-03-20 14:54:25+00" (sometimes with fractions of a second);
 * Cambridge writes local "2022-03-31T00:00:00.000" with no zone, which means Boston time.
 */
final class Timestamps {

    static final ZoneId BOSTON = ZoneId.of("America/New_York");

    private static final DateTimeFormatter WITH_OFFSET = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd[ ]['T']HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .appendOffset("+HH:mm", "Z")
            .toFormatter();
    private static final DateTimeFormatter WITH_OFFSET_HOURS = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd[ ]['T']HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .appendOffset("+HH", "Z")
            .toFormatter();
    private static final DateTimeFormatter LOCAL = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd['T'][ ]HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    private Timestamps() {
    }

    /** Null for a blank value; {@link IllegalArgumentException} for one that is not a date. */
    static OffsetDateTime parse(String text) {
        if (text == null) {
            return null;
        }
        for (DateTimeFormatter offset : new DateTimeFormatter[] {WITH_OFFSET, WITH_OFFSET_HOURS}) {
            try {
                return OffsetDateTime.parse(text, offset);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        try {
            return LocalDateTime.parse(text, LOCAL).atZone(BOSTON).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // try a bare date
        }
        try {
            return LocalDate.parse(text).atStartOfDay(BOSTON).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("unreadable date '" + text + "'");
        }
    }
}
