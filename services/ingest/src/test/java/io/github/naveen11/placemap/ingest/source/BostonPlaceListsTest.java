package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTReader;

/** Boston libraries, BCYF centres and open space, with rows from Analyze Boston (2026-10-11). */
class BostonPlaceListsTest {

    @Test
    void librariesAreNamedTheWayTheLibraryNamesItsBranches() throws Exception {
        var adapter = new BostonPublicLibraries();
        var rows = TestData.rows("""
                BRANCH,ST_ADDRESS,ZIPCODE,DISTRICT,POINT_X,POINT_Y
                West End,151 Cambridge St,02114,WEST END,-71.065005816999985,42.361438011000075
                Central,700 Boylston St,02116,BACK BAY,-71.078,42.3495
                """);

        SourceRow westEnd = ((RowResult.Accepted) adapter.parse(rows.get(0), TestData.parseContext())).row();
        assertThat(westEnd.name()).isEqualTo("Boston Public Library West End Branch");
        assertThat(westEnd.category()).isEqualTo("library");
        assertThat(westEnd.address()).isEqualTo("151 Cambridge St");
        assertThat(((RowResult.Accepted) adapter.parse(rows.get(1), TestData.parseContext())).row().name())
                .isEqualTo("Boston Public Library Central Library");
    }

    @Test
    void bcyfSitesGetTheirPublicNamesAndPoolsAreSkipped() throws Exception {
        var adapter = new BostonBcyfCenters();
        var rows = TestData.rows("""
                SITE,STREET,NEIGH,ZIP,PHONE,POINT_X,POINT_Y
                Pino,86 Boardman St.,East Boston,2128.000000000000000,635-5120,-71.010826982999959,42.388881714000036
                Charlestown*,255 Medford St.,Charlestown,2129.000000000000000,635-5169,-71.0663,42.3805
                Golden Age Center,382 Main St.,Charlestown,2129.000000000000000,635-5175,-71.0710,42.3780
                Paris Street Pool*,113 Paris St.,East Boston,2128.000000000000000,635-1410,-71.0365,42.3720
                BCYF Tobin Community Center,1481 Tremont St,Mission Hill,2120.000000000000000,,-71.1000,42.3330
                """);
        List<RowResult> parsed = rows.stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();

        SourceRow pino = ((RowResult.Accepted) parsed.get(0)).row();
        assertThat(pino.key()).isEqualTo("Pino");
        assertThat(pino.name()).isEqualTo("BCYF Pino Community Center");
        assertThat(pino.category()).isEqualTo("community_centre");
        assertThat(pino.postcode()).isEqualTo("02128");
        assertThat(pino.phone()).isEqualTo("617-635-5120");
        assertThat(((RowResult.Accepted) parsed.get(1)).row().name()).isEqualTo("BCYF Charlestown Community Center");
        assertThat(((RowResult.Accepted) parsed.get(2)).row().name()).isEqualTo("BCYF Golden Age Center");
        assertThat(parsed.get(3)).isEqualTo(RowResult.skipped("a pool, not a community centre"));
        SourceRow tobin = ((RowResult.Accepted) parsed.get(4)).row();
        assertThat(tobin.name()).isEqualTo("BCYF Tobin Community Center");
        assertThat(tobin.phone()).isNull();
    }

    // Trimmed from the real GeoJSON: an L-shaped playground (whose centroid lies outside it), a park in two parts,
    // a cemetery, and a parcel without geometry. Properties in the publisher's order and types.
    static final String OPEN_SPACE = """
            {"type": "FeatureCollection", "features": [
             {"type": "Feature", "properties": {"OBJECTID": 1, "SITE_NAME": "Reilly Playground", "OWNERSHIP": "Commonwealth of Massachusetts",
               "TYPECODE": 3, "DISTRICT": "Allston-Brighton", "ACRES": 6.93091232, "ADDRESS": "355 Chestnut Hill Avenue",
               "TypeLong": "Parks, Playgrounds & Athletic Fields", "OS_Own_Jur": "DCR", "OS_Mngmnt": "NULL",
               "ALT_NAME": "Reilly Memorial Recreation Center", "OS_ID": "3000", "ZipCode": "02135"},
              "geometry": {"type": "Polygon", "coordinates": [[[-71.150, 42.340], [-71.146, 42.340], [-71.146, 42.341],
                [-71.149, 42.341], [-71.149, 42.344], [-71.150, 42.344], [-71.150, 42.340]]]}},
             {"type": "Feature", "properties": {"SITE_NAME": "Franklin Park", "TypeLong": "Parks, Playgrounds & Athletic Fields",
               "OS_ID": "1001", "ZipCode": "02121", "ACRES": 485.0},
              "geometry": {"type": "MultiPolygon", "coordinates": [
                [[[-71.100, 42.300], [-71.080, 42.300], [-71.080, 42.315], [-71.100, 42.315], [-71.100, 42.300]]],
                [[[-71.095, 42.317], [-71.090, 42.317], [-71.090, 42.319], [-71.095, 42.319], [-71.095, 42.317]]]]}},
             {"type": "Feature", "properties": {"SITE_NAME": "Forest Hills Cemetery", "TypeLong": "Cemeteries & Burying Grounds",
               "OS_ID": "2002"},
              "geometry": {"type": "Polygon", "coordinates": [[[-71.110, 42.290], [-71.100, 42.290], [-71.100, 42.295],
                [-71.110, 42.290]]]}},
             {"type": "Feature", "properties": {"SITE_NAME": "Lost Parcel", "TypeLong": "Urban Wilds", "OS_ID": "4004"},
              "geometry": null}
            ]}
            """;

    @Test
    void openSpaceKeepsAPointInsideEachParcelAndItsOutline(@TempDir Path dir) throws Exception {
        var adapter = new BostonOpenSpace();
        Path geojson = dir.resolve("download.geojson");
        Files.writeString(geojson, OPEN_SPACE);
        Path target = dir.resolve("snapshot.csv");
        adapter.writeSnapshot(geojson, target);

        var rows = TestData.rows(target);
        assertThat(rows).extracting(r -> r.get("OS_ID")).containsExactly("1001", "2002", "3000", "4004");
        assertThat(rows.get(2).get("OS_Mngmnt")).as("the word NULL is a blank").isNull();

        var wkt = new WKTReader();
        var reilly = rows.get(2);
        var point = new GeometryFactory().createPoint(
                new Coordinate(Double.parseDouble(reilly.get("lon")), Double.parseDouble(reilly.get("lat"))));
        assertThat(wkt.read(reilly.get("outline")).covers(point)).as("the point lies inside the L").isTrue();
        assertThat(reilly.get("outline")).startsWith("MULTIPOLYGON (((");
        assertThat(wkt.read(rows.get(0).get("outline")).getNumGeometries()).isEqualTo(2);
    }

    @Test
    void parksBecomeOutlinedParkRecordsAndOtherOpenSpaceIsSkipped(@TempDir Path dir) throws Exception {
        var adapter = new BostonOpenSpace();
        Path geojson = dir.resolve("download.geojson");
        Files.writeString(geojson, OPEN_SPACE);
        Path target = dir.resolve("snapshot.csv");
        adapter.writeSnapshot(geojson, target);
        List<RowResult> parsed = TestData.rows(target).stream().map(r -> adapter.parse(r, TestData.parseContext())).toList();

        SourceRow franklin = ((RowResult.Accepted) parsed.get(0)).row();
        assertThat(franklin.name()).isEqualTo("Franklin Park");
        assertThat(franklin.category()).isEqualTo("park");
        assertThat(franklin.outline()).startsWith("MULTIPOLYGON");
        assertThat(parsed.get(1)).isEqualTo(RowResult.skipped("not a park: Cemeteries & Burying Grounds"));
        SourceRow reilly = ((RowResult.Accepted) parsed.get(2)).row();
        assertThat(reilly.address()).isEqualTo("355 Chestnut Hill Avenue");
        assertThat(reilly.attrs()).containsEntry("ALT_NAME", "Reilly Memorial Recreation Center")
                .containsEntry("ACRES", "6.93091232").doesNotContainKey("OS_Mngmnt");
        assertThat(parsed.get(3)).isEqualTo(RowResult.rejected("missing coordinates"));
    }

    @Test
    void openSpaceDownloadsTheGeoJsonAndAsksCkanForItsVersion(@TempDir Path dir) throws Exception {
        var asked = new ArrayList<URI>();
        var http = new HttpDownloader() {
            @Override
            public void download(URI uri, Path target) throws java.io.IOException {
                asked.add(uri);
                Files.writeString(target, OPEN_SPACE);
            }

            @Override
            public String getText(URI uri) {
                asked.add(uri);
                return "{\"result\": {\"last_modified\": \"2026-10-04T09:12:00\"}}";
            }
        };
        var adapter = new BostonOpenSpace();
        var source = TestData.catalog().get(BostonOpenSpace.ID);
        adapter.fetch(source, dir.resolve("snapshot.csv"), http);

        assertThat(adapter.remoteVersion(source, http)).isEqualTo("ckan:2026-10-04T09:12:00");
        assertThat(asked.get(1)).hasToString(
                "https://data.boston.gov/api/3/action/resource_show?id=ccc038ce-5602-42d0-b4c6-87f60c116ea3");
        assertThat(dir.resolve("open_space.geojson")).doesNotExist();
        assertThat(Files.readAllLines(dir.resolve("snapshot.csv"))).hasSize(5);
    }
}
