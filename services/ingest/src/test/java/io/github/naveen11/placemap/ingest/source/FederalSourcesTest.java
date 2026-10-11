package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FDIC bank offices and AFDC charging stations, with records trimmed from the real APIs (2026-10-11). */
class FederalSourcesTest {

    // Unsorted, as the API may return them: a full-service branch, a loan office, and a branch outside the area.
    static final String FDIC = """
            {"meta": {"total": 3, "index": {"name": "locations_20261009090008"}}, "data": [
             {"data": {"UNINUM": 11809, "CERT": "17870", "NAME": "Cambridge Savings Bank", "OFFNAME": "Cambridge Savings Bank",
               "ADDRESS": "1374 Massachusetts Ave", "CITY": "Cambridge", "STALP": "MA", "ZIP": "02138", "SERVTYPE": 11,
               "SERVTYPE_DESC": "Full Service - Brick and Mortar", "MAINOFF": 1, "LONGITUDE": -71.1190163954715,
               "LATITUDE": 42.3731794172773}, "score": 0},
             {"data": {"UNINUM": 10045, "CERT": "57957", "NAME": "Leader Bank, National Association",
               "OFFNAME": "Boston Loan Office", "ADDRESS": "100 Summer St", "CITY": "Boston", "STALP": "MA", "ZIP": "02110",
               "SERVTYPE": 24, "SERVTYPE_DESC": "LIMITED SERVICE - LOAN PRODUCTION", "MAINOFF": 0,
               "LONGITUDE": -71.0582, "LATITUDE": 42.3540}, "score": 0},
             {"data": {"UNINUM": 9001, "CERT": "90220", "NAME": "Eastern Bank", "OFFNAME": "Lynn Branch",
               "ADDRESS": "1 Market St", "CITY": "Lynn", "STALP": "MA", "ZIP": "01901", "SERVTYPE": 11,
               "SERVTYPE_DESC": "FULL SERVICE - BRICK AND MORTAR", "MAINOFF": 0, "LONGITUDE": -70.9497,
               "LATITUDE": 42.4626}, "score": 0}]}
            """;

    static final String AFDC = """
            {"station_locator_url": "https://afdc.energy.gov/stations/", "total_results": 2, "fuel_stations": [
             {"id": 38254, "station_name": "SEAPORT GARAGE EAST GARAGE2 P1", "street_address": "1 Seaport Ln",
              "city": "Boston", "state": "MA", "zip": "02210", "latitude": 42.349249, "longitude": -71.040928,
              "fuel_type_code": "ELEC", "access_code": "public", "status_code": "E", "ev_level1_evse_num": null,
              "ev_level2_evse_num": 2, "ev_dc_fast_num": null, "ev_network": "ChargePoint Network",
              "ev_connector_types": ["J1772"], "access_days_time": "24 hours daily", "facility_type": null,
              "owner_type_code": null, "date_last_confirmed": "2026-10-01", "station_phone": "888-758-4389"},
             {"id": 1517, "station_name": "Town Hall", "street_address": "333 Washington St", "city": "Brookline",
              "state": "MA", "zip": "02445", "latitude": 42.3329, "longitude": -71.1212, "fuel_type_code": "ELEC",
              "access_code": "public", "status_code": "E", "ev_level2_evse_num": 4, "ev_dc_fast_num": 1,
              "ev_network": "Non-Networked", "ev_connector_types": ["J1772", "J1772COMBO"]}]}
            """;

    private static SourceDefinition source(String id) {
        return TestData.catalog().get(id);
    }

    private static List<RowResult> parse(SourceAdapter adapter, Path snapshot) throws IOException {
        return TestData.rows(snapshot).stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();
    }

    @Test
    void fdicFetchAsksForMassachusettsOfficesAndSortsThemByUninum(@TempDir Path dir) throws Exception {
        var asked = new ArrayList<URI>();
        var http = new HttpDownloader() {
            @Override
            public String getText(URI uri) {
                asked.add(uri);
                return FDIC;
            }
        };
        var adapter = new FdicBankLocations();
        Path target = dir.resolve("snapshot.csv");
        adapter.fetch(source(FdicBankLocations.ID), target, http);

        assertThat(asked.getFirst().toString()).startsWith("https://api.fdic.gov/banks/locations?filters=STALP%3AMA&fields=UNINUM%2CCERT")
                .endsWith("&sort_by=UNINUM&sort_order=ASC&limit=10000&format=json");
        assertThat(Files.readAllLines(target)).extracting(l -> l.split(",")[0]).containsExactly("UNINUM", "10045", "11809", "9001");
        assertThat(adapter.remoteVersion(source(FdicBankLocations.ID), http)).isEqualTo("fdic:locations_20261009090008;3");
    }

    @Test
    void fullServiceBranchesBecomeBanksAndOtherOfficesAreSkipped(@TempDir Path dir) throws Exception {
        var adapter = new FdicBankLocations();
        Path target = dir.resolve("snapshot.csv");
        adapter.writeSnapshot(FDIC, target);
        List<RowResult> parsed = parse(adapter, target);

        assertThat(parsed.get(0)).isEqualTo(RowResult.skipped("not a full-service branch"));
        SourceRow csb = ((RowResult.Accepted) parsed.get(1)).row();
        assertThat(csb.key()).isEqualTo("11809");
        assertThat(csb.name()).isEqualTo("Cambridge Savings Bank");
        assertThat(csb.category()).isEqualTo("bank");
        assertThat(csb.address()).isEqualTo("1374 Massachusetts Ave");
        assertThat(csb.attrs()).containsEntry("OFFNAME", "Cambridge Savings Bank").containsEntry("SERVTYPE", "11");
        assertThat(parsed.get(2)).isEqualTo(RowResult.skipped(ParseContext.OUTSIDE_AREA));
    }

    @Test
    void aTruncatedFdicAnswerFailsTheFetch(@TempDir Path dir) {
        assertThatThrownBy(() -> new FdicBankLocations().writeSnapshot(FDIC.replace("\"total\": 3", "\"total\": 4"),
                dir.resolve("snapshot.csv")))
                .isInstanceOf(IOException.class).hasMessageContaining("returned 3 of 4");
    }

    @Test
    void afdcSendsItsKeyInAHeaderNeverInTheUrl(@TempDir Path dir) throws Exception {
        var asked = new ArrayList<URI>();
        var headers = new ArrayList<Map<String, String>>();
        var http = new HttpDownloader() {
            @Override
            public String getText(URI uri, Map<String, String> sent) {
                asked.add(uri);
                headers.add(sent);
                return uri.getPath().endsWith("last-updated.json") ? "{\"last_updated\":\"2026-10-11T05:03:46Z\"}" : AFDC;
            }
        };
        var adapter = new AfdcFuelStations(name -> "NLR_API_KEY".equals(name) ? "test-key" : null);
        adapter.fetch(source(AfdcFuelStations.ID), dir.resolve("snapshot.csv"), http);
        String version = adapter.remoteVersion(source(AfdcFuelStations.ID), http);

        assertThat(asked).extracting(URI::toString).containsExactly(
                "https://developer.nlr.gov/api/alt-fuel-stations/v1.json?state=MA&fuel_type=ELEC&access=public&status=E&limit=all",
                "https://developer.nlr.gov/api/alt-fuel-stations/v1/last-updated.json");
        assertThat(headers).containsOnly(Map.of("X-Api-Key", "test-key"));
        assertThat(version).isEqualTo("afdc:2026-10-11T05:03:46Z");
        assertThat(adapter.notReady(source(AfdcFuelStations.ID))).isEmpty();
    }

    @Test
    void withoutAKeyAfdcIsNotReadyAndItsFetchSaysWhy(@TempDir Path dir) {
        var adapter = new AfdcFuelStations(name -> "  ");

        assertThat(adapter.notReady(source(AfdcFuelStations.ID))).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("NLR_API_KEY is empty in .env"));
        assertThatThrownBy(() -> adapter.fetch(source(AfdcFuelStations.ID), dir.resolve("s.csv"), new HttpDownloader()))
                .isInstanceOf(IOException.class).hasMessageStartingWith("NLR_API_KEY is empty");
    }

    @Test
    void publicStationsBecomeEvChargingRecords(@TempDir Path dir) throws Exception {
        var adapter = new AfdcFuelStations(name -> "k");
        Path target = dir.resolve("snapshot.csv");
        adapter.writeSnapshot(AFDC, target);
        List<RowResult> parsed = parse(adapter, target);

        SourceRow townHall = ((RowResult.Accepted) parsed.get(0)).row(); // sorted by id as text: "1517" < "38254"
        assertThat(townHall.key()).isEqualTo("1517");
        assertThat(townHall.category()).isEqualTo("ev_charging");
        assertThat(townHall.attrs()).containsEntry("ev_connector_types", "J1772 J1772COMBO")
                .containsEntry("ev_dc_fast_num", "1").doesNotContainKey("ev_level1_evse_num");
        SourceRow seaport = ((RowResult.Accepted) parsed.get(1)).row();
        assertThat(seaport.phone()).isEqualTo("888-758-4389");
        assertThat(seaport.attrs()).containsEntry("access_days_time", "24 hours daily");
    }
}
