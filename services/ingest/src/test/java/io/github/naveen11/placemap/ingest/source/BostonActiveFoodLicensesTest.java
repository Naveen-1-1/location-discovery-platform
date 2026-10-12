package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BostonActiveFoodLicensesTest {

    private static final String HEADER =
            "businessname,dbaname,address,city,state,zip,licstatus,licensecat,descript,license_add_dt_tm,property_id,latitude,longitude\n";

    private final BostonActiveFoodLicenses adapter = new BostonActiveFoodLicenses();

    /** The columns we download, and therefore everything downstream, exclude the personal fields. */
    @Test
    void neverRequestsPersonalFields() {
        List<String> dropFields = TestData.catalog().get(BostonActiveFoodLicenses.ID).dropFields();

        assertThat(dropFields).isNotEmpty();
        assertThat(adapter.columns()).doesNotContainAnyElementsOf(dropFields);
        // The adapter set refuses to start if an adapter ever asks for one.
        new SourceAdapters(List.of(adapter), TestData.catalog());
    }

    @Test
    void sourceAdaptersRefuseAnAdapterThatAsksForAPersonalField() {
        var leaky = new BostonActiveFoodLicenses() {
            @Override
            public List<String> columns() {
                var columns = new ArrayList<>(super.columns());
                columns.add("dayphn_cleaned");
                return columns;
            }
        };
        assertThatThrownBy(() -> new SourceAdapters(List.of(leaky), TestData.catalog()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dayphn_cleaned");
    }

    @Test
    void downloadsOnlyItsColumnsSortedByTheNaturalKey() throws Exception {
        var requested = new ArrayList<URI>();
        var http = new HttpDownloader() {
            @Override
            public void download(URI uri, Path target) {
                requested.add(uri);
            }
        };
        adapter.fetch(TestData.catalog().get(BostonActiveFoodLicenses.ID), Path.of("unused.csv"), http);

        String query = URLDecoder.decode(requested.getFirst().getRawQuery(), StandardCharsets.UTF_8);
        assertThat(query).isEqualTo("fields=" + String.join(",", adapter.columns())
                + "&sort=property_id,businessname,licensecat,address");
        List<String> fields = List.of(query.substring("fields=".length(), query.indexOf('&')).split(","));
        assertThat(fields).doesNotContain("_id", "dayphn_cleaned"); // CKAN's row id changes on every reload
    }

    @Test
    void parsesEveryFixtureRow() throws Exception {
        List<CsvRow> rows = TestData.rows(TestData.BOSTON_LICENCES);
        ParseContext context = TestData.parseContext();

        var parsed = rows.stream().map(r -> adapter.parse(r, context)).toList();

        assertThat(parsed).hasSize(40).allSatisfy(r -> assertThat(r).isInstanceOf(RowResult.Accepted.class));
        List<SourceRow> records = parsed.stream().map(r -> ((RowResult.Accepted) r).row()).toList();
        assertThat(records).allSatisfy(r -> assertThat(r.category()).isEqualTo("food"));
        assertThat(records).filteredOn(r -> r.lat() == null).hasSize(10); // kept without a location
        assertThat(records.stream().map(SourceRow::key).distinct()).hasSize(40);
    }

    @Test
    void buildsTheRecordFromOneRow() throws Exception {
        CsvRow row = TestData.rows(HEADER
                + "Legal Name LLC,Corner Cafe,12  MAIN,Boston/,MA,02108,Active,FT,Eating & Drinking w/ Take Out,"
                + "2020-01-02 03:04:05+00,1234,42.3550,-71.0656\n").getFirst();

        SourceRow record = ((RowResult.Accepted) adapter.parse(row, TestData.parseContext())).row();

        assertThat(record.key()).isEqualTo("1234|Legal Name LLC|FT|12  MAIN");
        assertThat(record.name()).isEqualTo("Corner Cafe"); // the trading name is what is on the door
        assertThat(record.address()).isEqualTo("12 MAIN");
        assertThat(record.postcode()).isEqualTo("02108");
        assertThat(record.lon()).isEqualTo(-71.0656);
        assertThat(record.lat()).isEqualTo(42.3550);
        assertThat(record.ewkt()).isEqualTo("SRID=4326;POINT(-71.0656 42.355)");
        assertThat(record.category()).isEqualTo("food");
        assertThat(record.attrs()).containsEntry("businessname", "Legal Name LLC")
                .containsEntry("licensecat", "FT")
                .containsEntry("property_id", "1234")
                .doesNotContainKey("dayphn_cleaned");
    }

    @Test
    void rejectsBrokenCoordinatesButKeepsMissingOnes() throws Exception {
        var cases = Map.of(
                "42.3550,-71.0656", "accepted",
                ",", "accepted",                                       // no location: kept, geocoded later
                "-71.0656,42.3550", "latitude and longitude swapped",
                "0,0", "zero coordinate",
                "42.3370,-71.2092", "outside the coverage area",       // Newton
                "42.3550,", "only one of latitude and longitude",
                "north,west", "unreadable coordinates");
        cases.forEach((coords, expected) -> {
            try {
                CsvRow row = TestData.rows(HEADER + "Cafe,,1 MAIN,Boston/,MA,02108,Active,FS,Eating & Drinking,,1,"
                        + coords + "\n").getFirst();
                RowResult result = adapter.parse(row, TestData.parseContext());
                String outcome = result instanceof RowResult.Rejected r ? r.reason() : "accepted";
                assertThat(outcome).as(coords).isEqualTo(expected);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void rowsWithoutAKeyAreRejected() throws Exception {
        CsvRow row = TestData.rows(HEADER + ",,1 MAIN,Boston/,MA,02108,Active,FS,Eating & Drinking,,1,42.355,-71.0656\n")
                .getFirst();

        assertThat(adapter.key(row)).isNull();
        assertThat(adapter.parse(row, TestData.parseContext())).isInstanceOf(RowResult.Rejected.class);
    }
}
