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

/**
 * Downloads a URL to a file: first to {@code <file>.part}, then an atomic rename, so a crash never leaves a
 * half-written snapshot that looks complete. Runs on the run's virtual thread; an interrupt cancels the request.
 */
public class HttpDownloader {

    private static final String USER_AGENT =
            "place-discovery-map-ingest (+https://github.com/Naveen-1-1/place-discovery-map)";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

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
