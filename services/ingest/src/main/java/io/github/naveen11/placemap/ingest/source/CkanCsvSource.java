package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * A City of Boston dataset downloaded from its CKAN datastore dump (Analyze Boston). The dump API can return chosen
 * columns in a chosen order: we ask only for {@link #columns()}, so personal fields are never downloaded (ADR 0031),
 * and sort by the natural key, so unchanged data gives a byte-identical snapshot and the run is skipped.
 */
public abstract class CkanCsvSource implements SourceAdapter {

    /** Columns that order rows uniquely; never CKAN's {@code _id}, which changes when the dataset is reloaded. */
    protected abstract List<String> sortColumns();

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        if (!"ckan_csv".equals(source.access().method())) {
            throw new IllegalStateException(sourceId() + " is registered as " + source.access().method() + ", not ckan_csv");
        }
        String url = source.access().url()
                + "?fields=" + encode(String.join(",", columns()))
                + "&sort=" + encode(String.join(",", sortColumns()));
        http.download(URI.create(url), target);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
