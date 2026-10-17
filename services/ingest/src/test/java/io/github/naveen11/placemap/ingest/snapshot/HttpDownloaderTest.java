package io.github.naveen11.placemap.ingest.snapshot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** The downloader against a local server: headers sent, failures reported without the query, atomic files. */
class HttpDownloaderTest {

    @RegisterExtension
    static final WireMockExtension PUBLISHER = WireMockExtension.newInstance().options(options().dynamicPort()).build();

    private final HttpDownloader http = new HttpDownloader();

    @TempDir
    Path tmp;

    @Test
    void sendsItsUserAgentAndTheCallersHeaders() throws Exception {
        PUBLISHER.stubFor(get(urlPathEqualTo("/data.json")).willReturn(ok("{\"rows\":3}")));

        String body = http.getText(uri("/data.json?limit=3"), Map.of("X-App-Token", "secret"));

        assertThat(body).isEqualTo("{\"rows\":3}");
        PUBLISHER.verify(getRequestedFor(urlPathEqualTo("/data.json"))
                .withHeader("User-Agent", matching("location-discovery-platform-ingest .*"))
                .withHeader("X-App-Token", equalTo("secret")));
    }

    @Test
    void aFailedRequestNamesTheHostAndPathOnly() {
        PUBLISHER.stubFor(get(urlPathEqualTo("/data.json")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> http.getText(uri("/data.json?q=12+Elm+Street")))
                .isInstanceOf(IOException.class)
                .hasMessageEndingWith("/data.json returned HTTP 503")
                .hasMessageNotContaining("Elm");
    }

    @Test
    void headReportsTheVersionMarkers() throws Exception {
        PUBLISHER.stubFor(head(urlPathEqualTo("/gtfs.zip")).willReturn(ok()
                .withHeader("ETag", "\"v42\"").withHeader("Last-Modified", "Tue, 13 Oct 2026 10:00:00 GMT")));

        HttpDownloader.Head head = http.head(uri("/gtfs.zip"));

        assertThat(head.etag()).isEqualTo("\"v42\"");
        assertThat(head.lastModified()).isEqualTo("Tue, 13 Oct 2026 10:00:00 GMT");
    }

    @Test
    void aDownloadAppearsWholeOrNotAtAll() throws Exception {
        PUBLISHER.stubFor(get(urlPathEqualTo("/big.csv")).willReturn(ok("a,b\n1,2\n")));
        PUBLISHER.stubFor(get(urlPathEqualTo("/gone.csv")).willReturn(aResponse().withStatus(404)));
        Path target = tmp.resolve("snapshot.csv");

        http.download(uri("/big.csv"), target);
        assertThat(target).hasContent("a,b\n1,2\n");
        assertThat(tmp.resolve("snapshot.csv.part")).doesNotExist();

        Path failed = tmp.resolve("failed.csv");
        assertThatThrownBy(() -> http.download(uri("/gone.csv"), failed)).isInstanceOf(IOException.class);
        assertThat(failed).doesNotExist();
        assertThat(Files.list(tmp)).containsExactly(target);
    }

    private static URI uri(String pathAndQuery) {
        return URI.create(PUBLISHER.baseUrl() + pathAndQuery);
    }
}
