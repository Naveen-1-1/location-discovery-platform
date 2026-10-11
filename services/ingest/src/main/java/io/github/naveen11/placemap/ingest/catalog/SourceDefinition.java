package io.github.naveen11.placemap.ingest.catalog;

import java.time.Duration;
import java.util.List;

/** One entry of {@code data/sources.yaml}: only the fields the importer uses. */
public record SourceDefinition(
        String id,
        String name,
        Boolean enabled,
        String role,
        Access access,
        String cadence,
        String crs,
        List<String> dropFields) {

    public record Access(String method, String url, String apiKeyEnv) {
    }

    /** Sources are enabled unless the registry says {@code enabled: false} (e.g. ADR 0030). */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** Personal fields that must never be downloaded or stored (ADR 0031). */
    @Override
    public List<String> dropFields() {
        return dropFields == null ? List.of() : dropFields;
    }

    /** How often a fresh import is due. */
    public Duration refreshInterval() {
        return switch (cadence == null ? "" : cadence.strip()) {
            case "daily" -> Duration.ofDays(1);
            case "weekly" -> Duration.ofDays(7);
            case "monthly" -> Duration.ofDays(30);
            case "quarterly" -> Duration.ofDays(91);
            default -> throw new IllegalStateException(
                    "source " + id + " has cadence '" + cadence + "', which the importer does not schedule");
        };
    }
}
