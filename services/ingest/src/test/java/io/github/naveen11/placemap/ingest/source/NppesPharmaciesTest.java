package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.AreaPostcodes;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** NPPES pharmacies searched ZIP by ZIP, with a record shaped like the registry's answer on 2026-10-11. */
class NppesPharmaciesTest {

    private static final JsonMapper JSON = new JsonMapper();

    // The real record's structure; the authorised official's fields are made up but where the registry puts them.
    static String record(String number, String status, String dba, String taxonomy, String locationZip) {
        return """
                {"number": "%s", "enumeration_type": "NPI-2",
                 "basic": {"organization_name": "ATRIUS HEALTH, INC.", "status": "%s", "last_updated": "2023-03-07",
                   "authorized_official_first_name": "JANE", "authorized_official_last_name": "EXAMPLE",
                   "authorized_official_telephone_number": "6175550100"},
                 "other_names": [{"code": "4", "organization_name": "OLD NAME", "type": "Former Legal Business Name"}%s],
                 "taxonomies": [{"code": "%s", "desc": "Pharmacy, Community/Retail Pharmacy", "primary": true}],
                 "addresses": [
                   {"address_1": "275 GROVE ST", "address_purpose": "MAILING", "city": "AUBURNDALE", "postal_code": "024662272", "state": "MA"},
                   {"address_1": "1611 CAMBRIDGE ST", "address_purpose": "LOCATION", "city": "CAMBRIDGE",
                    "postal_code": "%s4302", "state": "MA", "telephone_number": "617-661-5538"}]}
                """.formatted(number, status,
                dba == null ? "" : ", {\"code\": \"3\", \"organization_name\": \"" + dba + "\", \"type\": \"Doing Business As\"}",
                taxonomy, locationZip);
    }

    private static String answer(String... records) {
        return "{\"result_count\": " + records.length + ", \"results\": [" + String.join(",", records) + "]}";
    }

    @Test
    void searchesEachZipAndKeepsEachPharmacyOnceWithoutItsOfficial(@TempDir Path dir) throws Exception {
        var asked = new ArrayList<String>();
        var http = new HttpDownloader() {
            @Override
            public String getText(URI uri) {
                String url = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
                asked.add(url);
                if (url.contains("postal_code=02138*")) {
                    return answer(record("1568595346", "A", "HARVARD SQ PHARMACY", "3336C0003X", "02138"));
                }
                if (url.contains("postal_code=02139*")) { // the same pharmacy again, found under another ZIP
                    return answer(record("1568595346", "A", "HARVARD SQ PHARMACY", "3336C0003X", "02138"),
                            record("1000000001", "A", null, "3336C0002X", "02139"));
                }
                return answer();
            }
        };
        var adapter = new NppesPharmacies(new AreaPostcodes(List.of("02138", "02139")));
        Path target = dir.resolve("snapshot.csv");
        adapter.fetch(TestData.catalog().get(NppesPharmacies.ID), target, http);

        assertThat(asked).hasSize(2).first().asString().startsWith("https://npiregistry.cms.hhs.gov/api/?version=2.1"
                + "&taxonomy_description=Pharmacy&enumeration_type=NPI-2&postal_code=02138*&limit=200&skip=0");
        String snapshot = Files.readString(target);
        assertThat(snapshot).doesNotContain("JANE", "EXAMPLE", "6175550100", "AUBURNDALE");
        assertThat(Files.readAllLines(target)).hasSize(3);
    }

    @Test
    void aZipWithMoreThanOneSearchCanReturnFailsTheFetch(@TempDir Path dir) {
        String full = answer(IntStream.range(0, 200)
                .mapToObj(i -> record(Integer.toString(1_000_000_000 + i), "A", null, "3336C0003X", "02138"))
                .toArray(String[]::new));
        var http = new HttpDownloader() {
            @Override
            public String getText(URI uri) {
                return full;
            }
        };
        var adapter = new NppesPharmacies(new AreaPostcodes(List.of("02138")));
        assertThatThrownBy(() -> adapter.fetch(TestData.catalog().get(NppesPharmacies.ID), dir.resolve("s.csv"), http))
                .isInstanceOf(IOException.class).hasMessageContaining("more than 1200 pharmacies under ZIP 02138");
    }

    @Test
    void activePharmaciesInTheTownsBecomeRecordsWaitingForTheGeocoder(@TempDir Path dir) throws Exception {
        var adapter = new NppesPharmacies(TestData.postcodes());
        Path target = dir.resolve("snapshot.csv");
        SnapshotFiles.writeSorted(target, adapter.columns(), List.of(
                NppesPharmacies.row(JSON.readTree(record("1568595346", "A", "HARVARD SQ PHARMACY", "3336C0003X", "02138"))),
                NppesPharmacies.row(JSON.readTree(record("1000000002", "D", null, "3336C0003X", "02138"))),
                NppesPharmacies.row(JSON.readTree(record("1000000003", "A", null, "3336C0003X", "01950")))));
        List<RowResult> parsed = TestData.rows(target).stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();

        assertThat(parsed.get(0)).isEqualTo(RowResult.skipped("deactivated NPI"));
        assertThat(parsed.get(1)).isEqualTo(RowResult.skipped("practice address outside the four towns' ZIP codes"));
        SourceRow harvard = ((RowResult.Accepted) parsed.get(2)).row();
        assertThat(harvard.name()).isEqualTo("HARVARD SQ PHARMACY");
        assertThat(harvard.category()).isEqualTo("pharmacy");
        assertThat(harvard.lon()).isNull();
        assertThat(harvard.address()).isEqualTo("1611 CAMBRIDGE ST");
        assertThat(harvard.postcode()).isEqualTo("02138");
        assertThat(harvard.attrs()).containsAllEntriesOf(Map.of("organization_name", "ATRIUS HEALTH, INC.",
                "city", "CAMBRIDGE", "taxonomy_code", "3336C0003X"));
        assertThat(adapter.geocodesMissingLocations()).isTrue();
    }
}
