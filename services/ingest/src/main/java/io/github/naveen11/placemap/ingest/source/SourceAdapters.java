package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sources this importer can handle, checked against the registry at startup. Fails fast if an adapter would
 * request a personal field or names a source the registry does not have.
 */
public class SourceAdapters {

    private final Map<String, SourceAdapter> byId = new LinkedHashMap<>();

    public SourceAdapters(List<SourceAdapter> adapters, SourceCatalog catalog) {
        for (SourceAdapter adapter : adapters) {
            SourceDefinition source = catalog.get(adapter.sourceId());
            List<String> personal = new ArrayList<>(adapter.columns());
            personal.retainAll(source.dropFields());
            if (!personal.isEmpty()) {
                throw new IllegalStateException(adapter.sourceId() + " requests personal fields " + personal
                        + " that data/sources.yaml lists under drop_fields");
            }
            if (byId.put(adapter.sourceId(), adapter) != null) {
                throw new IllegalStateException("two adapters for " + adapter.sourceId());
            }
        }
    }

    public SourceAdapter get(String sourceId) {
        SourceAdapter adapter = byId.get(sourceId);
        if (adapter == null) {
            throw new IllegalArgumentException("no importer for source '" + sourceId + "'; implemented: " + byId.keySet());
        }
        return adapter;
    }

    public Collection<SourceAdapter> all() {
        return byId.values();
    }
}
