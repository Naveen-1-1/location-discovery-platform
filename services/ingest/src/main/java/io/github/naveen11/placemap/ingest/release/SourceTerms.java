package io.github.naveen11.placemap.ingest.release;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * What {@code data/sources.yaml} says about each source's licence: the part the data release needs (ADR 0044), which
 * the importer's {@code SourceDefinition} leaves out.
 *
 * @param redistribution {@code yes}, {@code display-only} or {@code unknown}
 * @param providers      Overture only: the upstream providers its records name, and what each needs in the release
 */
public record SourceTerms(
        String id,
        String name,
        String publisher,
        Boolean enabled,
        String redistribution,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> licence,
        String licenceUrl,
        String attribution,
        List<String> dropFields,
        Map<String, Provider> providers) {

    /**
     * One upstream provider of Overture records.
     *
     * @param credit  the line the release prints for it; null for Overture's own records
     * @param licence its SPDX id, which decides the licence text the release ships
     * @param notice  a file in {@code data/licences} the release must ship word for word
     */
    public record Provider(String credit, String licence, String notice) {
    }

    private record SourcesFile(List<SourceTerms> sources) {
    }

    /** By source id, in file order. */
    public static Map<String, SourceTerms> load(Path file) {
        var mapper = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        var terms = new LinkedHashMap<String, SourceTerms>();
        for (SourceTerms source : mapper.readValue(file, SourcesFile.class).sources()) {
            if (terms.put(source.id(), source) != null) {
                throw new IllegalStateException("duplicate source id in " + file + ": " + source.id());
            }
        }
        return terms;
    }

    /** Imported, and its terms let it into the ODbL release. */
    public boolean releasable() {
        return isEnabled() && "yes".equals(redistribution);
    }

    public boolean displayOnly() {
        return "display-only".equals(redistribution);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Override
    public List<String> licence() {
        return licence == null ? List.of() : licence;
    }

    @Override
    public List<String> dropFields() {
        return dropFields == null ? List.of() : dropFields;
    }

    @Override
    public Map<String, Provider> providers() {
        return providers == null ? Map.of() : providers;
    }
}
