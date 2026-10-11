package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The GBFS feed to snapshot to records, with real docks from the 2026-10-11 feed. */
class BluebikesStationsTest {

    // Trimmed from the real feed: keys in the publisher's order, one dock without region_id, and one outside the area.
    static final String FEED = """
            {"last_updated": 1791744266, "ttl": 60, "version": "1.1", "data": {"stations": [
             {"station_id": "f83497b9-0de8-11e7-991c-3863bb43a7d0", "name": "Harvard Square at Mass Ave/ Dunster",
              "short_name": "M32018", "lon": -71.118579, "lat": 42.373268, "region_id": "8", "capacity": 19,
              "rental_uris": {"ios": "https://bos.lft.to/lastmile_qr_scan"}, "legacy_id": "74", "has_kiosk": true,
              "station_type": "classic", "eightd_station_services": []},
             {"station_id": "6eaa7343-402d-41c9-9aac-8c664fdbdcf1", "name": "Salem MBTA - Lower Level",
              "short_name": "T32012", "lon": -70.89684, "lat": 42.523994, "capacity": 11, "has_kiosk": true,
              "station_type": "classic"},
             {"station_id": "f83493c0-0de8-11e7-991c-3863bb43a7d0", "name": "MIT at Mass Ave / Amherst St",
              "short_name": "M32006", "lon": -71.0932723, "lat": 42.3583056, "region_id": "8", "capacity": 31,
              "has_kiosk": true, "station_type": "classic"}
            ]}}
            """;

    private final BluebikesStations adapter = new BluebikesStations();

    @Test
    void fetchWritesTheFixedColumnsSortedByStationId(@TempDir Path dir) throws Exception {
        var http = new HttpDownloader() {
            @Override
            public String getText(URI uri) {
                assertThat(uri).hasToString("https://gbfs.bluebikes.com/gbfs/en/station_information.json");
                return FEED;
            }
        };
        Path target = dir.resolve("snapshot.csv");
        adapter.fetch(TestData.catalog().get(BluebikesStations.ID), target, http);

        List<String> lines = Files.readAllLines(target);
        assertThat(lines.getFirst()).isEqualTo(String.join(",", adapter.columns()));
        assertThat(lines.subList(1, lines.size())).containsExactly(
                "6eaa7343-402d-41c9-9aac-8c664fdbdcf1,T32012,Salem MBTA - Lower Level,-70.89684,42.523994,11,,classic,true",
                "f83493c0-0de8-11e7-991c-3863bb43a7d0,M32006,MIT at Mass Ave / Amherst St,-71.0932723,42.3583056,31,8,classic,true",
                "f83497b9-0de8-11e7-991c-3863bb43a7d0,M32018,Harvard Square at Mass Ave/ Dunster,-71.118579,42.373268,19,8,classic,true");
    }

    @Test
    void docksInsideTheAreaBecomeBikeShareRecordsAndOthersAreSkipped(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("snapshot.csv");
        adapter.writeSnapshot(FEED, target);
        List<RowResult> parsed = TestData.rows(target).stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();

        assertThat(parsed.getFirst()).isEqualTo(RowResult.skipped(ParseContext.OUTSIDE_AREA));
        SourceRow mit = ((RowResult.Accepted) parsed.get(1)).row();
        assertThat(mit.key()).isEqualTo("f83493c0-0de8-11e7-991c-3863bb43a7d0");
        assertThat(mit.name()).isEqualTo("MIT at Mass Ave / Amherst St");
        assertThat(mit.category()).isEqualTo("bike_share");
        assertThat(mit.lon()).isEqualTo(-71.0932723);
        assertThat(mit.attrs()).containsEntry("capacity", "31").containsEntry("short_name", "M32006");
        assertThat(parsed.get(2)).isInstanceOf(RowResult.Accepted.class);
    }

    @Test
    void aDockWithoutCoordinatesIsRejected() throws Exception {
        var rows = TestData.rows(String.join(",", adapter.columns()) + "\nabc,X1,Somewhere,,,5,,classic,true\n");
        assertThat(adapter.parse(rows.getFirst(), TestData.parseContext())).isInstanceOf(RowResult.Rejected.class);
    }
}
