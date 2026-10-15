package io.github.naveen11.placemap.ingest;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Our PostGIS image (PostGIS and H3, built from infra/docker/postgis) run through Testcontainers' Postgres module,
 * and a data source on it with every migration applied. A container test declares
 * {@code @Container static final PostgreSQLContainer DB = TestDatabase.postgis();}.
 */
public final class TestDatabase {

    private static final Path IMAGE_DIR = Path.of("../../infra/docker/postgis").toAbsolutePath().normalize();

    private TestDatabase() {
    }

    /** Builds the image on first use (kept between runs) and describes a container of it. */
    public static PostgreSQLContainer postgis() {
        String image = new ImageFromDockerfile("placemap/postgis-test", false).withFileFromPath(".", IMAGE_DIR).get();
        return new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("placemap");
    }

    /** The container's JDBC URL with batched inserts rewritten, as the importer runs in production. */
    public static String url(PostgreSQLContainer db) {
        String url = db.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "reWriteBatchedInserts=true";
    }

    /** A data source on {@code db} with every migration applied. */
    public static DataSource migrated(PostgreSQLContainer db) {
        var dataSource = new DriverManagerDataSource(url(db), db.getUsername(), db.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return dataSource;
    }
}
