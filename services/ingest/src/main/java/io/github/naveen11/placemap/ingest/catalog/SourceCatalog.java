package io.github.naveen11.placemap.ingest.catalog;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The data-source registry, {@code data/sources.yaml}. */
public final class SourceCatalog {

    private record SourcesFile(List<SourceDefinition> sources) {
    }

    private final Map<String, SourceDefinition> byId;

    private SourceCatalog(Map<String, SourceDefinition> byId) {
        this.byId = byId;
    }

    public static SourceCatalog load(Path file) {
        var mapper = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // the registry documents far more than we read
                .build();
        var parsed = mapper.readValue(file, SourcesFile.class);
        var byId = new LinkedHashMap<String, SourceDefinition>();
        for (var source : parsed.sources()) {
            if (byId.put(source.id(), source) != null) {
                throw new IllegalStateException("duplicate source id in " + file + ": " + source.id());
            }
        }
        return new SourceCatalog(Map.copyOf(byId));
    }

    public Optional<SourceDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public SourceDefinition get(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("no source '" + id + "' in data/sources.yaml"));
    }
}
