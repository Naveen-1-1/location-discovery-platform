package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A City of Boston dataset downloaded from its CKAN datastore dump (Analyze Boston). The dump API can return chosen
 * columns in a chosen order: we ask only for {@link #columns()}, so personal fields are never downloaded (ADR 0031),
 * and sort by the natural key, so unchanged data gives a byte-identical snapshot and the run is skipped.
 */
public abstract class CkanCsvSource implements SourceAdapter {

    private static final JsonMapper JSON = new JsonMapper();

    /** Columns that order rows uniquely; never CKAN's {@code _id}, which changes when the dataset is reloaded. */
    protected abstract List<String> sortColumns();

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        download(source, http, columns(), target);
    }

    /**
     * Downloads the chosen fields sorted by {@link #sortColumns()}. Sources that turn the rows into something else
     * (one row per park, per Wi-Fi site) download to a working file and write their snapshot from it.
     */
    protected void download(SourceDefinition source, HttpDownloader http, List<String> fields, Path target)
            throws IOException, InterruptedException {
        if (!"ckan_csv".equals(source.access().method())) {
            throw new IllegalStateException(sourceId() + " is registered as " + source.access().method() + ", not ckan_csv");
        }
        String url = source.access().url()
                + "?fields=" + encode(String.join(",", fields))
                + "&sort=" + encode(String.join(",", sortColumns()));
        http.download(URI.create(url), target);
    }

    /** The resource's last-modified time from CKAN's metadata API: a few hundred bytes instead of the whole file. */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        URI dump = URI.create(source.access().url());
        return resourceVersion(http, dump, Path.of(dump.getPath()).getFileName().toString());
    }

    /** A CKAN resource's last-modified time, from the metadata API of the portal {@code url} is on. */
    static String resourceVersion(HttpDownloader http, URI url, String resourceId) throws IOException, InterruptedException {
        URI metadata = URI.create(url.getScheme() + "://" + url.getHost() + "/api/3/action/resource_show?id="
                + encode(resourceId));
        JsonNode result = JSON.readTree(http.getText(metadata)).required("result");
        String modified = result.path("last_modified").asString(null);
        return "ckan:" + (modified != null ? modified : result.required("metadata_modified").asString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
