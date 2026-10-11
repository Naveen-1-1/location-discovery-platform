package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import io.github.naveen11.placemap.ingest.TestData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SNAP retailers, Licensing Board licences, park features and Wi-Fi sites, with real rows from 2026-10-11. */
class EnrichmentSourcesTest {

    @Test
    void snapRetailersKeepTheirTypeAndStoreTheUnitOutsideTheAddress() throws Exception {
        var adapter = new UsdaSnapRetailers(TestData.area());
        var rows = TestData.rows("""
                Record_ID,Store_Name,Store_Street_Address,Additonal_Address,City,State,Zip_Code,Store_Type,Incentive_Program,lon,lat
                1203001,Star Market 4521,45 William T Morrissey Blvd,Ste 2,Dorchester,MA,02125,Supermarket,,-71.0496,42.3205
                1203002,Bova's Bakery,134 Salem St,,Boston,MA,02113,Specialty Store,,-71.0550,42.3646
                """);

        SourceRow star = ((RowResult.Accepted) adapter.parse(rows.get(0), TestData.parseContext())).row();
        assertThat(star.category()).isEqualTo("grocery");
        assertThat(star.address()).isEqualTo("45 William T Morrissey Blvd");
        assertThat(star.attrs()).containsEntry("Additonal_Address", "Ste 2").containsEntry("Store_Type", "Supermarket");
        SourceRow bakery = ((RowResult.Accepted) adapter.parse(rows.get(1), TestData.parseContext())).row();
        assertThat(bakery.category()).as("still matched, for the SNAP mark").isNull();
    }

    static final String LICENCES = """
            license_num,status,license_category,license_type,expires,business_name,dba_name,opening,closing,patronsout,capacity,address,city,state,zip,gpsx,gpsy
            LB-100001,Active,Common Victualler,Common Victualler,2026-12-31,Mary Louise Skudris,The Galley Diner,5:00 AM,8:00 PM,8:30 PM,28,11-  P ST,South Boston,MA,02127,783955.6049185544,2948480.6649758965
            LB-100008,Active,Common Victualler,Common Victualler,2026-12-31,"Tezanlar, Inc.",Thornton's Restaurant,7:00 AM,11:00 PM,11:30 PM,66,150-  Huntington AV,Boston,MA,02115,769178.7489736378,2950969.3473344743
            LB-200310,Active,Misc,Dormitory,2026-12-31,Some College,,,,,120,10  Campus RD,Boston,MA,02115,769000,2950000
            LB-596687,Active,Common Victualler,CV7 All Alc by Zip Restricted,2026-12-31,"Wash El Beverages, LLC",,,2:00 AM,,90,1395-1405  Washington ST,Boston,MA,02118,,
            LB-628439,Active,Common Victualler,Common Victualler,2026-12-31,Mike's Pastry Inc.,Mike's Pastry,5:00 AM,10:00 PM,,0,300-  Logan Airport Terminal C,East Boston,MA,02128,0,0
            """;

    @Test
    void licencesAreReprojectedFromStatePlaneFeetAndKeepTheirHours() throws Exception {
        var adapter = new BostonLicensingBoard();
        List<RowResult> parsed = TestData.rows(LICENCES).stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();

        SourceRow galley = ((RowResult.Accepted) parsed.get(0)).row();
        assertThat(galley.name()).isEqualTo("The Galley Diner");
        assertThat(galley.category()).isEqualTo("food");
        // 11 P St, South Boston, where Photon (an independent geocoder) puts it: within about 20 m.
        assertThat(galley.lon()).isCloseTo(-71.02734, offset(0.0003));
        assertThat(galley.lat()).isCloseTo(42.33779, offset(0.0002));
        assertThat(galley.address()).as("an unfinished range is just a number").isEqualTo("11 P ST");
        assertThat(galley.attrs()).containsEntry("opening", "5:00 AM").containsEntry("closing", "8:00 PM")
                .doesNotContainKeys("applicant", "manager", "day_phone");
        assertThat(parsed.get(2)).isEqualTo(RowResult.skipped("lodging, not a place to visit"));
        SourceRow noPoint = ((RowResult.Accepted) parsed.get(3)).row();
        assertThat(noPoint.name()).as("the legal name when there is no trading name").isEqualTo("Wash El Beverages, LLC");
        assertThat(noPoint.lon()).as("kept without a location, for the geocoder").isNull();
        assertThat(noPoint.address()).isEqualTo("1395-1405 Washington ST");
        SourceRow zeros = ((RowResult.Accepted) parsed.get(4)).row();
        assertThat(zeros.lon()).as("0, 0 means not located, not a point in the Atlantic").isNull();
    }

    @Test
    void aLicenceRepeatedAsIdenticalRowsIsOneRecordNotARejection() {
        var adapter = new BostonLicensingBoard();
        assertThat(adapter.linesPerRecord()).isTrue();
        assertThat(adapter.sortColumns()).startsWith("license_num");
    }

    @Test
    void parkAssetsBecomeOneRecordPerParkThatJoinsTheParcel(@TempDir Path dir) throws Exception {
        var adapter = new BostonParkFeatures();
        Path assets = dir.resolve("assets.csv");
        Files.writeString(assets, """
                OS_ID,Park_Name,Asset
                90,Dorchester Park,Athletic Seating
                90,Dorchester Park,Playground
                90,Dorchester Park,Athletic Seating
                375,,Basketball
                375,Ronan Park,Basketball
                375,Ronan Park,Dog Park
                ,Orphan,Bench
                """);
        Path target = dir.resolve("snapshot.csv");
        adapter.writeSnapshot(assets, target);

        assertThat(Files.readAllLines(target)).containsExactly(
                "OS_ID,Park_Name,features,assets",
                "375,Ronan Park,Basketball:2;Dog Park:1,3",
                "90,Dorchester Park,Athletic Seating:2;Playground:1,3");
        SourceRow ronan = ((RowResult.Accepted) adapter.parse(TestData.rows(target).getFirst(), TestData.parseContext())).row();
        assertThat(ronan.key()).isEqualTo("375");
        assertThat(ronan.lon()).isNull();
        assertThat(ronan.attrs()).containsEntry("features", "Basketball:2;Dog Park:1");
        assertThat(adapter.joinsSource()).contains(BostonOpenSpace.ID);
    }

    @Test
    void wifiAccessPointsWithin75MetresOfEachOtherAreOneSite(@TempDir Path dir) throws Exception {
        var adapter = new BostonWickedFreeWifi();
        Path devices = dir.resolve("devices.csv");
        // Three access points in one centre, a street network with two far apart, and a mobile kit.
        Files.writeString(devices, """
                device_serial,neighborhood_name,device_address,device_lat,device_long,inside_outside
                Q2AA-0003,BCYF-Tremont,"1483 Tremont St Boston, MA",42.332229,-71.098239,
                Q2AA-0001,BCYF-Tremont,"1483 Tremont St Boston, MA",42.332300,-71.098300,Inside
                Q2AA-0002,BCYF-Tremont,,42.332100,-71.098100,Inside
                Q2BB-0001,Roxbury,"2300 Washington St, Roxbury",42.329000,-71.084000,
                Q2BB-0002,Roxbury,"200 Dudley St, Roxbury",42.326000,-71.077000,
                Q2ZY-V6UL,Mobile WiFi Kit 3,,,,
                """);
        Path target = dir.resolve("snapshot.csv");
        adapter.writeSnapshot(devices, target);
        var rows = TestData.rows(target);

        assertThat(rows).extracting(r -> r.get("site")).containsExactly(
                "BCYF-Tremont|Q2AA-0001", "Mobile WiFi Kit 3|Q2ZY-V6UL", "Roxbury|Q2BB-0001", "Roxbury|Q2BB-0002");
        var tremont = rows.getFirst();
        assertThat(tremont.get("access_points")).isEqualTo("3");
        assertThat(tremont.get("address")).isEqualTo("1483 Tremont St Boston, MA");
        assertThat(Double.parseDouble(tremont.get("lat"))).isCloseTo(42.332210, offset(0.000001));
        assertThat(tremont.get("inside_outside")).isEqualTo("Inside");

        List<RowResult> parsed = rows.stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();
        SourceRow site = ((RowResult.Accepted) parsed.getFirst()).row();
        assertThat(site.name()).isEqualTo("Wicked Free WiFi · BCYF Tremont");
        assertThat(site.category()).isEqualTo("wifi");
        assertThat(site.address()).isEqualTo("1483 Tremont St Boston");
        assertThat(parsed.get(1)).isEqualTo(RowResult.skipped("no location (mobile or unplaced access point)"));
    }

    @Test
    void wifiSiteNamesDropTheNetworksTechnicalWords() {
        assertThat(BostonWickedFreeWifi.siteName("BCYF-Vine-CC_ Indoor")).isEqualTo("Wicked Free WiFi · BCYF Vine CC");
        assertThat(BostonWickedFreeWifi.siteName("Strand Theatre - Internal")).isEqualTo("Wicked Free WiFi · Strand Theatre");
        assertThat(BostonWickedFreeWifi.siteName("")).isEqualTo("Wicked Free WiFi · Boston");
    }
}
