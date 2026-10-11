package io.github.naveen11.placemap.ingest;

import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;

/**
 * A downloader for tests that must not touch the network: subclasses serve downloads from local files, and the
 * publishers' version markers are unavailable, so runs fall back to downloading and comparing the snapshot.
 */
public abstract class OfflineHttp extends HttpDownloader {

    @Override
    public String getText(URI uri) throws IOException {
        throw new IOException("offline test: no metadata for " + uri.getHost());
    }

    @Override
    public Head head(URI uri) throws IOException {
        throw new IOException("offline test: no HEAD for " + uri.getHost());
    }
}
