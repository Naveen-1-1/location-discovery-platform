package io.github.naveen11.placemap.ingest.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One line of a record's inspection history (ADR 0034): the inspection it belongs to and, if the line reports one, a
 * violation. An inspection is identified by record, time and result (Boston has 506 inspections with two results at
 * the same time).
 *
 * @param recordKey the source key of the inspected establishment's record
 */
public record InspectionLine(String recordKey, OffsetDateTime inspectedAt, String result, Violation violation) {

    /** Inspectors' comments are cut to this length to keep the full history within the free database (ADR 0034). */
    public static final int MAX_COMMENT = 280;

    public InspectionLine {
        Objects.requireNonNull(recordKey, "recordKey");
        Objects.requireNonNull(inspectedAt, "inspectedAt");
        Objects.requireNonNull(result, "result");
    }

    /**
     * @param level severity, 1 to 3 (Boston writes it as "*" to "***")
     */
    public record Violation(String code, String description, Integer level, String status, OffsetDateTime citedAt,
            OffsetDateTime statusDate, String comment) {

        public Violation {
            if (comment != null && comment.length() > MAX_COMMENT) {
                comment = comment.substring(0, MAX_COMMENT);
            }
        }

        /**
         * The line's identity within its inspection: a hash of everything it says. Identical lines (the source repeats
         * some) collapse into one, and re-importing a line never duplicates it.
         */
        public byte[] lineHash() {
            String text = String.join("\u001f", String.valueOf(code), String.valueOf(level), String.valueOf(status),
                    String.valueOf(citedAt), String.valueOf(statusDate), String.valueOf(comment));
            try {
                return MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("every JDK ships MD5", e);
            }
        }
    }
}
