package io.github.naveen11.placemap.livehub.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BodyLimitFilterTest {

    private final BodyLimitFilter filter = new BodyLimitFilter(64 * 1024);

    @Test
    void aBodyDeclaredOverTheCapIs413WithoutBeingRead() throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/live/batch");
        request.setContent(new byte[64 * 1024 + 1]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader(HttpHeaders.CONNECTION)).isEqualTo("close");
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"status\":413").contains("limited to 65536 bytes");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requestsWithinTheCapOrOfUnknownLengthPassUnwrapped() throws Exception {
        var small = new MockHttpServletRequest("POST", "/internal/live/batch");
        small.setContent(new byte[64 * 1024]);
        var chunked = new MockHttpServletRequest("POST", "/internal/live/batch");
        chunked.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");

        for (var request : new MockHttpServletRequest[] {small, chunked, new MockHttpServletRequest("GET", "/live")}) {
            var chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isSameAs(request);
        }
    }
}
