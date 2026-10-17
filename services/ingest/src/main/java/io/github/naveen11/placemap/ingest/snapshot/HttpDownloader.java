package io.github.naveen11.placemap.ingest.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

/**
 * Downloads a URL to a file: first to {@code <file>.part}, then an atomic rename, so a crash never leaves a
 * half-written snapshot that looks complete. Runs on the run's virtual thread; an interrupt cancels the request.
 */
public class HttpDownloader {

    private static final String USER_AGENT =
            "location-discovery-platform-ingest (+https://github.com/Naveen-1-1/location-discovery-platform)";

    private final HttpClient client;

    public HttpDownloader() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build());
    }

    /** With a client of the caller's, such as one that goes through a proxy. */
    public HttpDownloader(HttpClient client) {
        this.client = client;
    }

    /** What a HEAD request says about a file, after redirects. */
    public record Head(URI uri, String etag, String lastModified) {
    }

    /** A text response held in memory: metadata JSON, or a JSON API answer of a few megabytes. */
    public String getText(URI uri) throws IOException, InterruptedException {
        return getText(uri, Map.of());
    }

    /**
     * A text response with extra request headers, such as an API key. Keys go in headers rather than the URL, so they
     * never appear in a logged URI; error messages name only the host and path either way.
     */
    public String getText(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(2))
                .GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GET " + uri.getHost() + uri.getPath() + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    public Head head(URI uri) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IOException("HEAD " + uri.getHost() + uri.getPath() + " returned HTTP " + response.statusCode());
        }
        return new Head(response.uri(), response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("Last-Modified").orElse(null));
    }

    public void download(URI uri, Path target) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("GET " + uri.getHost() + uri.getPath() + " returned HTTP " + response.statusCode());
            }
            Path part = target.resolveSibling(target.getFileName() + ".part");
            try (OutputStream out = Files.newOutputStream(part)) {
                body.transferTo(out);
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
