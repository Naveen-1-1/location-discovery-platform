package io.github.naveen11.placemap.api.bodylimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class BodyLimitFilterTest {

    private final Counter rejected = new SimpleMeterRegistry().counter("placemap.bodylimit.rejected");
    private final BodyLimitFilter filter = new BodyLimitFilter(1024, rejected);

    @Test
    void aTenMegabyteBodyIs413WithoutReadingAByte() throws Exception {
        var request = new UnreadableBodyRequest(10L * 1024 * 1024);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader(HttpHeaders.CONNECTION)).isEqualTo("close");
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"status\":413").contains("over 1024 bytes");
        assertThat(chain.getRequest()).isNull();
        assertThat(request.reads).isZero();
        assertThat(rejected.count()).isEqualTo(1);
    }

    @Test
    void aChunkedBodyIs411WithoutReadingAByte() throws Exception {
        var request = new UnreadableBodyRequest(-1);
        request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(411);
        assertThat(response.getHeader(HttpHeaders.CONNECTION)).isEqualTo("close");
        assertThat(chain.getRequest()).isNull();
        assertThat(request.reads).isZero();
    }

    @Test
    void requestsWithoutABodyOrWithASmallOnePass() throws Exception {
        var get = new MockHttpServletRequest("GET", "/api/places/nearby");
        var small = new MockHttpServletRequest("POST", "/api/places/nearby");
        small.setContent(new byte[1024]);

        for (var request : new MockHttpServletRequest[] {get, small}) {
            var chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isSameAs(request);
        }
        assertThat(rejected.count()).isZero();
    }

    @Test
    void coversEveryPathAfterTheMetricsFilter() {
        var registration = new BodyLimitConfig().bodyLimitFilter(
                new BodyLimitConfig.BodyLimitProperties(DataSize.ofKilobytes(1)), new SimpleMeterRegistry());

        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getOrder()).isGreaterThan(Integer.MIN_VALUE + 1).isLessThan(Integer.MIN_VALUE + 10);
    }

    /** Declares a body of the given length (-1: unknown) and counts any attempt to read it. */
    private static final class UnreadableBodyRequest extends MockHttpServletRequest {

        private final long length;
        int reads;

        UnreadableBodyRequest(long length) {
            super("POST", "/api/places/nearby");
            this.length = length;
        }

        @Override
        public long getContentLengthLong() {
            return length;
        }

        @Override
        public int getContentLength() {
            return (int) Math.min(length, Integer.MAX_VALUE);
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override
                public int read() {
                    reads++;
                    return 'x';
                }

                @Override
                public boolean isFinished() {
                    return false;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }
            };
        }
    }
}
