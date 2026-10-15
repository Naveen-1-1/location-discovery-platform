package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each publisher's "has it changed?" marker, read from the shapes of their real metadata responses (2026-10-10). */
class RemoteVersionTest {

    /** Answers metadata requests with a fixed body and records what was asked. */
    private static final class Metadata extends HttpDownloader {
        final List<URI> asked = new ArrayList<>();
        final String body;
        final Head head;

        Metadata(String body, Head head) {
            this.body = body;
            this.head = head;
        }

        @Override
        public String getText(URI uri) {
            asked.add(uri);
            return body;
        }

        @Override
        public Head head(URI uri) {
            asked.add(uri);
            return head;
        }

        @Override
        public void download(URI uri, Path target) {
            throw new AssertionError("a version check must not download " + uri);
        }
    }

    @Test
    void bostonUsesTheResourceLastModifiedTime() throws Exception {
        var http = new Metadata("""
                {"success": true, "result": {"last_modified": "2026-10-10T14:46:26.705686",
                 "metadata_modified": "2026-10-10T14:46:26.721761", "datastore_active": true}}
                """, null);

        String version = new BostonActiveFoodLicenses().remoteVersion(TestData.catalog().get(BostonActiveFoodLicenses.ID), http);

        assertThat(version).isEqualTo("ckan:2026-10-10T14:46:26.705686");
        assertThat(http.asked).singleElement().hasToString(
                "https://data.boston.gov/api/3/action/resource_show?id=f1e13724-284d-478c-b8bc-ef042aa5b70b");
    }

    @Test
    void cambridgeUsesTheViewsRowUpdateTime() throws Exception {
        var http = new Metadata("""
                {"id": "iect-ma2e", "rowsUpdatedAt": 1791625569, "viewLastModified": 1791625542}
                """, null);

        String version = new CambridgeFoodPermits().remoteVersion(TestData.catalog().get(CambridgeFoodPermits.ID), http);

        assertThat(version).isEqualTo("socrata:1791625569;1791625542");
        assertThat(http.asked).singleElement().hasToString("https://data.cambridgema.gov/api/views/iect-ma2e.json");
    }

    @Test
    void osmUsesTheDatedFileAndItsEtag() throws Exception {
        var http = new Metadata(null, new HttpDownloader.Head(
                URI.create("https://download.geofabrik.de/north-america/us/massachusetts-261009.osm.pbf"),
                "\"128820d3-65d8255cc8548\"", "Sat, 10 Oct 2026 20:30:26 GMT"));

        String version = new OsmSource(TestData.area(), TestData.categories(), "osmium")
                .remoteVersion(TestData.catalog().get(OsmSource.ID), http);

        assertThat(version).isEqualTo("osm:/north-america/us/massachusetts-261009.osm.pbf;\"128820d3-65d8255cc8548\"");
    }

    @Test
    void overtureReleasesArePinnedInTheUrl() throws Exception {
        String version = new OvertureSource(TestData.area(), TestData.nativeCalls())
                .remoteVersion(TestData.catalog().get(OvertureSource.ID), new Metadata(null, null));

        assertThat(version).isEqualTo("overture:s3://overturemaps-us-west-2/release/2026-09-23.1/theme=places/type=place/");
    }
}
