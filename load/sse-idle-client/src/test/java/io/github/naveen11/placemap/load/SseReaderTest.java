package io.github.naveen11.placemap.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The response reader against what live-hub's servlet sends through Tomcat: chunked SSE. */
class SseReaderTest {

    @Test
    void readsEventsAcrossChunksAndSkipsHeartbeats() throws IOException {
        String body = "retry: 4321\n\n"
                + "id: 7\nevent: snapshot\ndata: {\"topic\":\"bikes\",\"items\":[]}\n\n"
                + ": hb\n\n"
                + "id: 8\nevent: bikes\ndata: {\"t\":1760400000123,\"id\":\"a\",\"bikes\":3}\n\n";
        // Chunk boundaries fall inside lines, as Tomcat's do.
        String chunked = chunk(body.substring(0, 20)) + chunk(body.substring(20, 75)) + chunk(body.substring(75)) + "0\r\n\r\n";
        var reader = new SseReader(stream("HTTP/1.1 200 \r\nContent-Type: text/event-stream\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n" + chunked));

        assertThat(reader.readHead()).isEqualTo(200);
        SseReader.Event snapshot = reader.next();
        assertThat(snapshot).isEqualTo(new SseReader.Event("snapshot", "{\"topic\":\"bikes\",\"items\":[]}"));
        assertThat(reader.retryMillis()).isEqualTo(4321);
        SseReader.Event change = reader.next();
        assertThat(change.name()).isEqualTo("bikes");
        assertThat(SseReader.publishedMillis(change.data())).isEqualTo(1760400000123L);
        assertThat(reader.next()).isNull();
    }

    @Test
    void aRefusalCarriesItsRetryAfter() throws IOException {
        var reader = new SseReader(stream("HTTP/1.1 429 \r\nRetry-After: 10\r\nContent-Length: 0\r\n\r\n"));

        assertThat(reader.readHead()).isEqualTo(429);
        assertThat(reader.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void onlyDeltasHaveAPublicationTime() {
        assertThat(SseReader.publishedMillis("{\"topic\":\"bikes\",\"stale\":false}")).isEqualTo(-1);
        assertThat(SseReader.publishedMillis("{\"t\":}")).isEqualTo(-1);
        assertThat(SseReader.publishedMillis("{\"t\":5}")).isEqualTo(5);
    }

    @Test
    void lagPercentilesComeFromTheBuckets() {
        var histogram = new SseIdleClient.LagHistogram();
        for (int i = 1; i <= 100; i++) {
            histogram.record(i);
        }
        histogram.record(99_999); // later than 30 s: the last bucket
        long[] counts = histogram.snapshot();

        assertThat(SseIdleClient.LagHistogram.percentile(counts, 0.5)).isEqualTo(51);
        assertThat(SseIdleClient.LagHistogram.percentile(counts, 0.99)).isEqualTo(100);
        assertThat(SseIdleClient.LagHistogram.max(counts)).isEqualTo(30_000);
        assertThat(SseIdleClient.LagHistogram.percentile(new long[3], 0.99)).isEqualTo(-1);
    }

    private static String chunk(String s) {
        return Integer.toHexString(s.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + s + "\r\n";
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
