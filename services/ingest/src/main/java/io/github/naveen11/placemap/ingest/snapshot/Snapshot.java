package io.github.naveen11.placemap.ingest.snapshot;

import java.nio.file.Path;

/** A saved input file and its content hash. */
public record Snapshot(Path file, String sha256, long bytes) {
}
