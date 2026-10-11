package io.github.naveen11.placemap.ingest;

import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A downloader for tests that talk to the real publishers. Java's HTTP client ignores {@code HTTPS_PROXY}, which
 * sandboxes and CI runners often use, so this one honours it, credentials included. Without it, a plain downloader.
 */
public final class LiveHttp {

    private LiveHttp() {
    }

    public static HttpDownloader create() {
        String proxy = System.getenv("HTTPS_PROXY");
        if (proxy == null || proxy.isBlank()) {
            return new HttpDownloader();
        }
        URI uri = URI.create(proxy);
        var builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort())));
        if (uri.getRawUserInfo() != null) {
            // Basic credentials for an HTTPS tunnel also need -Djdk.http.auth.tunneling.disabledSchemes= on the JVM.
            String[] user = uri.getRawUserInfo().split(":", 2);
            var credentials = new PasswordAuthentication(URLDecoder.decode(user[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(user.length > 1 ? user[1] : "", StandardCharsets.UTF_8).toCharArray());
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY ? credentials : null;
                }
            });
        }
        return new HttpDownloader(builder.build());
    }
}
