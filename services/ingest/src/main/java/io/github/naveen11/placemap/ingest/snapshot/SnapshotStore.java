package io.github.naveen11.placemap.ingest.snapshot;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved inputs under {@code <raw-dir>/<source>/<run_id>/} (ADR 0020): {@code snapshot.csv} plus a small
 * {@code manifest.json}. Batches are always cut from this file, so a resumed run sees exactly the rows it started
 * with. Only the latest run's snapshot is kept per source.
 */
public class SnapshotStore {

    static final String FILE = "snapshot.csv";

    private final Path rawDir;
    private final HttpDownloader http;
    private final JsonMapper json = new JsonMapper();

    public SnapshotStore(Path rawDir, HttpDownloader http) {
        this.rawDir = rawDir;
        this.http = http;
    }

    /** The client snapshots are downloaded with; also used to read publishers' version markers. */
    public HttpDownloader http() {
        return http;
    }

    /** Downloads a fresh snapshot for the run, replacing any partial one, and checks its columns. */
    public Snapshot fetch(SourceAdapter adapter, SourceDefinition source, long runId)
            throws IOException, InterruptedException {
        Path dir = runDir(source.id(), runId);
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE);
        adapter.fetch(source, file, http);
        try (var parser = SnapshotCsv.open(file)) {
            SnapshotCsv.checkHeader(parser, adapter.columns(), file);
        }
        Snapshot snapshot = new Snapshot(file, sha256(file), Files.size(file));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("source", source.id());
        manifest.put("run_id", runId);
        manifest.put("url", source.access().url());
        manifest.put("columns", adapter.columns());
        manifest.put("bytes", snapshot.bytes());
        manifest.put("sha256", snapshot.sha256());
        manifest.put("fetched_at", Instant.now().toString());
        json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("manifest.json").toFile(), manifest);
        return snapshot;
    }

    /** The run's saved snapshot, if it is still on disk. */
    public Optional<Snapshot> existing(String source, long runId) throws IOException {
        Path file = runDir(source, runId).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(file, sha256(file), Files.size(file)));
    }

    /** Deletes every other run's directory for the source. */
    public void keepOnly(String source, long runId) throws IOException {
        Path sourceDir = rawDir.resolve(source);
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        String keep = Long.toString(runId);
        try (Stream<Path> runs = Files.list(sourceDir)) {
            for (Path run : runs.filter(p -> !p.getFileName().toString().equals(keep)).toList()) {
                deleteTree(run);
            }
        }
    }

    Path runDir(String source, long runId) {
        return rawDir.resolve(source).resolve(Long.toString(runId));
    }

    static String sha256(Path file) throws IOException {
        try (var in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
