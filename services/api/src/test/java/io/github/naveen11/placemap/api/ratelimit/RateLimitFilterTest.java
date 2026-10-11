package io.github.naveen11.placemap.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private final Counter rejected = new SimpleMeterRegistry().counter("placemap.ratelimit.rejected");
    private final RateLimitFilter filter = new RateLimitFilter(
            new ClientRateLimiter(new RateLimitProperties(true, 1, 2, 100), TimeMeter.SYSTEM_NANOTIME), rejected);

    @Test
    void overTheLimitAnswers429WithRetryAfterAndAProblemDetail() throws Exception {
        assertThat(send("198.51.100.4").getStatus()).isEqualTo(200);
        assertThat(send("198.51.100.4").getStatus()).isEqualTo(200);

        MockHttpServletResponse over = send("198.51.100.4");

        assertThat(over.getStatus()).isEqualTo(429);
        assertThat(over.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(over.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE + ";charset=UTF-8");
        assertThat(over.getContentAsString()).contains("\"status\":429").contains("Try again in 1 s.");
        assertThat(rejected.count()).isEqualTo(1);
    }

    @Test
    void requestsWithinTheLimitReachTheApplication() throws Exception {
        var chain = new MockFilterChain();
        filter.doFilter(request("198.51.100.4"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void onlyTheApiIsLimited() {
        var registration = new RateLimitConfig().rateLimitFilter(
                new ClientRateLimiter(new RateLimitProperties(true, 10, 20, 100), TimeMeter.SYSTEM_NANOTIME),
                new SimpleMeterRegistry());

        assertThat(registration.getUrlPatterns()).containsExactly("/api/*");
    }

    private MockHttpServletResponse send(String address) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(address), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String address) {
        var request = new MockHttpServletRequest("GET", "/api/places/nearby");
        request.setRemoteAddr(address);
        return request;
    }
}
