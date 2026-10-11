package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ArcGIS FeatureServer paging and errors, through the MassGIS hospitals layer (records from 2026-10-11). */
class ArcgisFeatureSourceTest {

    private static final String LAYER = "https://arcgisserver.digital.mass.gov/arcgisserver/rest/services/AGOL/Hospitals/FeatureServer/0";

    private static final String METADATA = """
            {"name": "Hospitals", "maxRecordCount": 2, "objectIdField": "OBJECTID", "fields": [
              {"name": "OBJECTID"}, {"name": "DPH_FACILITY_ID"}, {"name": "FACILITY_NAME"}, {"name": "FACILITY_TYPE"},
              {"name": "STREET_ADDRESS"}, {"name": "MUNICIPALITY"}, {"name": "ZIP_CODE"}, {"name": "PHONE"},
              {"name": "TOTAL_BEDS"}, {"name": "EMERGENCY_DEPT"}, {"name": "TRAUMA_LEVEL_ADULT"},
              {"name": "TRAUMA_LEVEL_PEDS"}, {"name": "MAD_ID"}]}
            """;

    // Two pages of two, then one: paging stops at the short page. Anna Jaques is in Newburyport, outside the area.
    private static final List<String> PAGES = List.of("""
            {"features": [
             {"attributes": {"DPH_FACILITY_ID": "2001", "FACILITY_NAME": "CHA Somerville Hospital",
               "FACILITY_TYPE": "Non-acute Hospital", "STREET_ADDRESS": "230 Highland Avenue", "MUNICIPALITY": "SOMERVILLE",
               "ZIP_CODE": "02143", "PHONE": "(617) 591-4500", "TOTAL_BEDS": 69.0, "EMERGENCY_DEPT": "No",
               "TRAUMA_LEVEL_ADULT": "N/A", "TRAUMA_LEVEL_PEDS": "N/A", "MAD_ID": 3212846},
              "geometry": {"x": -71.10951372999826, "y": 42.389998728371154}},
             {"attributes": {"DPH_FACILITY_ID": "2006", "FACILITY_NAME": "Anna Jaques Hospital",
               "FACILITY_TYPE": "Acute Hospital", "STREET_ADDRESS": "25 Highland Avenue", "MUNICIPALITY": "NEWBURYPORT",
               "ZIP_CODE": "01950", "PHONE": "(978) 463-1010", "TOTAL_BEDS": 113.0, "EMERGENCY_DEPT": "Yes",
               "TRAUMA_LEVEL_ADULT": "N/A", "TRAUMA_LEVEL_PEDS": "N/A", "MAD_ID": null},
              "geometry": {"x": -70.88, "y": 42.81}}],
             "exceededTransferLimit": true}
            """, """
            {"features": [
             {"attributes": {"DPH_FACILITY_ID": "2048", "FACILITY_NAME": "Boston Children's Hospital",
               "FACILITY_TYPE": "Acute Hospital", "STREET_ADDRESS": "300 Longwood Avenue", "MUNICIPALITY": "BOSTON",
               "ZIP_CODE": "02115", "PHONE": "(617) 355-6000", "TOTAL_BEDS": 404.0, "EMERGENCY_DEPT": "Yes",
               "TRAUMA_LEVEL_ADULT": "N/A", "TRAUMA_LEVEL_PEDS": "Level I", "MAD_ID": 75466},
              "geometry": {"x": -71.10566, "y": 42.33743}},
             {"attributes": {"DPH_FACILITY_ID": "2010", "FACILITY_NAME": "No Point Hospital"}, "geometry": null}],
             "exceededTransferLimit": true}
            """, """
            {"features": []}
            """);

    private final MassgisHospitals adapter = new MassgisHospitals(TestData.area());

    /** Serves the metadata, then the pages in order, and keeps the query URLs. */
    private static final class FakeLayer extends HttpDownloader {
        final List<String> queries = new ArrayList<>();
        private final String metadata;
        private final List<String> pages;

        FakeLayer(String metadata, List<String> pages) {
            this.metadata = metadata;
            this.pages = pages;
        }

        @Override
        public String getText(URI uri) throws IOException {
            String url = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
            if (url.equals(LAYER + "?f=json")) {
                return metadata;
            }
            queries.add(url);
            if (queries.size() > pages.size()) {
                throw new IOException("asked for page " + queries.size());
            }
            return pages.get(queries.size() - 1);
        }
    }

    @Test
    void pagesThroughTheLayerAskingForWgs84InsideTheAreaOnly(@TempDir Path dir) throws Exception {
        var http = new FakeLayer(METADATA, PAGES);
        Path target = dir.resolve("snapshot.csv");
        adapter.fetch(TestData.catalog().get(MassgisHospitals.ID), target, http);

        assertThat(http.queries).hasSize(3);
        assertThat(http.queries.getFirst())
                .startsWith(LAYER + "/query?where=1=1&geometry={\"xmin\":-71.19")
                .contains("&outSR=4326", "&orderByFields=DPH_FACILITY_ID ASC", "&resultOffset=0&resultRecordCount=2",
                        "&outFields=DPH_FACILITY_ID,FACILITY_NAME,FACILITY_TYPE,");
        assertThat(http.queries.get(1)).contains("&resultOffset=2&");
        assertThat(http.queries.get(2)).contains("&resultOffset=4&");

        List<String> lines = Files.readAllLines(target);
        assertThat(lines.getFirst()).isEqualTo(String.join(",", adapter.columns())).endsWith(",MAD_ID,lon,lat");
        assertThat(lines.subList(1, lines.size())).extracting(l -> l.substring(0, 4))
                .containsExactly("2001", "2006", "2010", "2048"); // sorted by the key, whatever the page order
    }

    @Test
    void featuresBecomeHospitalRecordsAndOthersAreSkippedOrRejected(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("snapshot.csv");
        adapter.fetch(TestData.catalog().get(MassgisHospitals.ID), target, new FakeLayer(METADATA, PAGES));
        List<RowResult> parsed = TestData.rows(target).stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();

        SourceRow somerville = ((RowResult.Accepted) parsed.get(0)).row();
        assertThat(somerville.key()).isEqualTo("2001");
        assertThat(somerville.name()).isEqualTo("CHA Somerville Hospital");
        assertThat(somerville.category()).isEqualTo("hospital");
        assertThat(somerville.lon()).isEqualTo(-71.10951372999826);
        assertThat(somerville.address()).isEqualTo("230 Highland Avenue");
        assertThat(somerville.phone()).isEqualTo("(617) 591-4500");
        assertThat(somerville.attrs()).containsEntry("TOTAL_BEDS", "69.0").containsEntry("EMERGENCY_DEPT", "No");
        assertThat(parsed.get(1)).isEqualTo(RowResult.skipped(ParseContext.OUTSIDE_AREA));
        assertThat(parsed.get(2)).isEqualTo(RowResult.rejected("missing coordinates"));
        assertThat(((RowResult.Accepted) parsed.get(3)).row().attrs()).containsEntry("TRAUMA_LEVEL_PEDS", "Level I");
    }

    @Test
    void anArcgisErrorOrAMissingFieldFailsTheFetch(@TempDir Path dir) {
        var error = new FakeLayer(METADATA, List.of("{\"error\": {\"code\": 400, \"message\": \"Invalid query\"}}"));
        assertThatThrownBy(() -> adapter.fetch(TestData.catalog().get(MassgisHospitals.ID), dir.resolve("a.csv"), error))
                .isInstanceOf(IOException.class).hasMessageContaining("ArcGIS error 400 Invalid query");

        var drifted = new FakeLayer(METADATA.replace("{\"name\": \"PHONE\"},", ""), PAGES);
        assertThatThrownBy(() -> adapter.fetch(TestData.catalog().get(MassgisHospitals.ID), dir.resolve("b.csv"), drifted))
                .isInstanceOf(IOException.class).hasMessageContaining("no longer has fields [PHONE]");
    }

    @Test
    void theRemoteVersionIsTheLayersLastDataEditWhenTheServerTracksIt() throws Exception {
        var untracked = new FakeLayer(METADATA, List.of());
        assertThat(adapter.remoteVersion(TestData.catalog().get(MassgisHospitals.ID), untracked)).isNull();

        var tracked = new FakeLayer(METADATA.replace("\"maxRecordCount\": 2,",
                "\"maxRecordCount\": 2, \"editingInfo\": {\"dataLastEditDate\": 1791394759439},"), List.of());
        assertThat(adapter.remoteVersion(TestData.catalog().get(MassgisHospitals.ID), tracked))
                .isEqualTo("arcgis:1791394759439");
    }
}
