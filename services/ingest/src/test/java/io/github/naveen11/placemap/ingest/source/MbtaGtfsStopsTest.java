package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** GTFS zip to snapshot to records, with real rows from MBTA's stops.txt of 2026-10-09. */
class MbtaGtfsStopsTest {

    // The real header (with the byte-order mark MBTA's files carry) and one row of each kind.
    static final String STOPS = "﻿stop_id,stop_code,stop_name,stop_desc,platform_code,platform_name,stop_lat,"
            + "stop_lon,zone_id,stop_address,stop_url,level_id,location_type,parent_station,wheelchair_boarding,"
            + "municipality,on_street,at_street,vehicle_type\n"
            + "place-harsq,,Harvard,,,,42.373362,-71.118956,,\"1400 Massachusetts Ave, Cambridge, MA\","
            + "https://www.mbta.com/stops/place-harsq,,1,,1,Cambridge,,,\n"
            + "70068,70068,Harvard,Harvard - Red Line - Alewife,,Alewife,42.374663,-71.118814,RapidTransit,,"
            + "https://www.mbta.com/stops/place-harsq,level_-1_alewife_platform,0,place-harsq,1,Cambridge,,,1\n"
            + "door-harsq-brattle,,Brattle Square,Brattle Square,,,42.37331,-71.120545,,,,level_in_street,2,"
            + "place-harsq,1,Cambridge,,,\n"
            + "1,1,Washington St opp Ruggles St,,,,42.330957,-71.082754,ExpressBus-Downtown,,"
            + "https://www.mbta.com/stops/1,,0,,1,Boston,Washington Street,Ruggles Street,3\n"
            + "14460,14460,Highland Ave @ Salem Hospital,,,,42.511791,-70.908936,LocalBus,,"
            + "https://www.mbta.com/stops/14460,,0,,0,Salem,Highland Avenue,,3\n";

    private final MbtaGtfsStops adapter = new MbtaGtfsStops();

    @Test
    void fetchKeepsTheFixedColumnsSortedByStopIdAndDeletesTheZip(@TempDir Path dir) throws Exception {
        var http = new HttpDownloader() {
            @Override
            public void download(URI uri, Path target) throws java.io.IOException {
                assertThat(uri).hasToString("https://cdn.mbta.com/MBTA_GTFS.zip");
                writeZip(target);
            }
        };
        Path target = dir.resolve("snapshot.csv");
        adapter.fetch(TestData.catalog().get(MbtaGtfsStops.ID), target, http);

        List<String> lines = Files.readAllLines(target);
        assertThat(lines.getFirst()).isEqualTo(String.join(",", adapter.columns()));
        assertThat(lines.subList(1, lines.size())).extracting(l -> l.substring(0, l.indexOf(',')))
                .containsExactly("1", "14460", "70068", "door-harsq-brattle", "place-harsq");
        assertThat(lines.get(1)).isEqualTo(
                "1,1,Washington St opp Ruggles St,42.330957,-71.082754,0,,1,Boston,Washington Street,Ruggles Street,3");
        try (var files = Files.list(dir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void stationsAndStreetStopsBecomeTransitRecords(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("gtfs.zip");
        writeZip(zip);
        Path target = dir.resolve("snapshot.csv");
        adapter.writeSnapshot(zip, target);
        List<RowResult> parsed = TestData.rows(target).stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();

        SourceRow bus = ((RowResult.Accepted) parsed.get(0)).row();
        assertThat(bus.key()).isEqualTo("1");
        assertThat(bus.category()).isEqualTo("transit");
        assertThat(bus.attrs()).containsEntry("vehicle_type", "3").containsEntry("on_street", "Washington Street");
        assertThat(parsed.get(1)).isEqualTo(RowResult.skipped(ParseContext.OUTSIDE_AREA));
        assertThat(parsed.get(2)).isEqualTo(RowResult.skipped("platform inside a station"));
        assertThat(parsed.get(3)).isEqualTo(RowResult.skipped("no app category")); // an entrance
        SourceRow station = ((RowResult.Accepted) parsed.get(4)).row();
        assertThat(station.key()).isEqualTo("place-harsq");
        assertThat(station.name()).isEqualTo("Harvard");
        assertThat(station.attrs()).containsEntry("location_type", "1").containsEntry("wheelchair_boarding", "1");
    }

    @Test
    void usesTheZipsEtagAsItsVersion() throws Exception {
        var http = new HttpDownloader() {
            @Override
            public Head head(URI uri) {
                return new Head(uri, "\"40c7a39134e6cd419e36977204eb1af6-3\"", "Fri, 09 Oct 2026 18:03:54 GMT");
            }
        };
        assertThat(adapter.remoteVersion(TestData.catalog().get(MbtaGtfsStops.ID), http))
                .isEqualTo("gtfs:\"40c7a39134e6cd419e36977204eb1af6-3\"");
    }

    private static void writeZip(Path target) throws java.io.IOException {
        try (OutputStream out = Files.newOutputStream(target); var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("agency.txt"));
            zip.write("agency_id\n1\n".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("stops.txt"));
            zip.write(STOPS.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
