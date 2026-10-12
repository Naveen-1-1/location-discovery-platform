package io.github.naveen11.placemap.livehub.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BodyLimitFilterTest {

    private final BodyLimitFilter filter = new BodyLimitFilter(64 * 1024);

    @Test
    void aChunkedBodyIsReadNoFurtherThanOneBytePastTheCap() throws Exception {
        var body = new CountingStream(new byte[1024 * 1024]);
        var chain = new MockFilterChain();

        filter.doFilter(new ChunkedRequest(body), new MockHttpServletResponse(), chain);
        ServletInputStream in = chain.getRequest().getInputStream();

        assertThatThrownBy(in::readAllBytes).isInstanceOf(IOException.class).hasMessageContaining("over 65536 bytes");
        assertThat(body.read).isEqualTo(64 * 1024 + 1);
    }

    @Test
    void aChunkedBodyUnderTheCapReadsWhole() throws Exception {
        var chain = new MockFilterChain();

        filter.doFilter(new ChunkedRequest(new CountingStream(new byte[64 * 1024])), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest().getInputStream().readAllBytes()).hasSize(64 * 1024);
    }

    @Test
    void requestsWithoutABodyPassUnwrapped() throws Exception {
        var request = new MockHttpServletRequest("GET", "/live");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    /** A body of unknown length, as the api's client sends it. */
    private static final class ChunkedRequest extends MockHttpServletRequest {

        private final ServletInputStream body;

        ChunkedRequest(ServletInputStream body) {
            super("POST", "/internal/live/batch");
            addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
            this.body = body;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public ServletInputStream getInputStream() {
            return body;
        }
    }

    /** Counts the bytes handed out, however they are asked for. */
    private static final class CountingStream extends ServletInputStream {

        private final ByteArrayInputStream bytes;
        int read;

        CountingStream(byte[] bytes) {
            this.bytes = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            int b = bytes.read();
            read += b >= 0 ? 1 : 0;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int n = bytes.read(buffer, offset, length);
            read += Math.max(n, 0);
            return n;
        }

        @Override
        public boolean isFinished() {
            return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
        }
    }
}
