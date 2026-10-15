package io.github.naveen11.placemap.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.OfflineHttp;
import io.github.naveen11.placemap.ingest.TestDatabase;
import io.github.naveen11.placemap.ingest.geocode.Candidate;
import io.github.naveen11.placemap.ingest.geocode.Geocoder;
import io.github.naveen11.placemap.ingest.geocode.Geocoding;
import io.github.naveen11.placemap.ingest.geocode.JdbcGeocodeCache;
import io.github.naveen11.placemap.ingest.run.RunCoordinator.Outcome;
import io.github.naveen11.placemap.ingest.source.BostonActiveFoodLicenses;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The geocode stage end to end, on the real migrations: licences the city never located are placed through the cache
 * table, and a second run asks the geocoder nothing. Needs Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class GeocodeContainerTest {

    private static final String SOURCE = BostonActiveFoodLicenses.ID;

    @Container
    static final PostgreSQLContainer POSTGIS = TestDatabase.postgis();

    private static DataSource dataSource;
    private static JdbcClient jdbc;

    @TempDir
    Path tmp;

    @BeforeAll
    static void migrate() {
        dataSource = TestDatabase.migrated(POSTGIS);
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void unlocatedLicencesArePlacedOnceAndTheAnswerIsReused() throws Exception {
        Path licences = tmp.resolve("licences.csv");
        Files.writeString(licences, String.join("\n",
                "businessname,dbaname,address,city,state,zip,licstatus,licensecat,descript,license_add_dt_tm,property_id,latitude,longitude",
                "Corner Cafe LLC,Corner Cafe,12 Main St,Boston,MA,02108,Active,FS,Eating & Drinking,2020-01-01 00:00:00+00,1,42.3560,-71.0600",
                "Harbor Grill LLC,Harbor Grill,40 Water St,Boston,MA,02109,Active,FS,Eating & Drinking,2020-01-01 00:00:00+00,2,,",
                "Nowhere Diner LLC,Nowhere Diner,99 Lost Ln,Boston,MA,02110,Active,FS,Eating & Drinking,2020-01-01 00:00:00+00,3,,")
                + "\n");
        var calls = new AtomicInteger();
        Geocoder photon = query -> {
            calls.incrementAndGet();
            return query.startsWith("40 water st")
                    ? List.of(new Candidate(-71.0570, 42.3580, "40", "Water Street", "02109", "40 Water Street, Boston"))
                    : List.of();
        };
        var cache = new JdbcGeocodeCache(dataSource, new DataSourceTransactionManager(dataSource));
        var geocoding = new Geocoding(photon, cache, 100);
        var http = new OfflineHttp() {
            @Override
            public void download(URI uri, Path target) throws IOException {
                Files.copy(licences, target, StandardCopyOption.REPLACE_EXISTING);
            }
        };

        assertThat(run(geocoding, http, false)).isEqualTo(Outcome.COMPLETE);
        assertThat(calls).as("one call per unlocated address").hasValue(2);
        assertThat(jdbc.sql("""
                        SELECT round(ST_X(geom::geometry)::numeric, 4) || ' ' || round(ST_Y(geom::geometry)::numeric, 4)
                        FROM place.source_record WHERE source = ? AND source_key LIKE '2|%'
                        """).param(SOURCE).query(String.class).single()).isEqualTo("-71.0570 42.3580");
        assertThat(jdbc.sql("SELECT attrs->>'geocode' FROM place.source_record WHERE source = ? AND source_key LIKE '3|%'")
                .param(SOURCE).query(String.class).single()).isEqualTo("none");
        assertThat(jdbc.sql("SELECT quality FROM ingest.geocode_cache ORDER BY query").query(String.class).list())
                .containsExactly("house", "none");

        assertThat(run(geocoding, http, true)).isEqualTo(Outcome.COMPLETE);
        assertThat(calls).as("the second run is answered from the cache").hasValue(2);
    }

    private Outcome run(Geocoding geocoding, OfflineHttp http, boolean force) {
        return TestIngest.coordinator(dataSource, TestIngest.properties(tmp.resolve("raw"), 2, 0.2), http, geocoding,
                new BostonActiveFoodLicenses()).run(SOURCE, force);
    }
}
