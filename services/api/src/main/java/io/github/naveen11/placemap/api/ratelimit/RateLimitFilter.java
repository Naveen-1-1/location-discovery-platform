package io.github.naveen11.placemap.api.ratelimit;

import io.github.naveen11.placemap.api.Problems;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Answers 429 with Retry-After and a problem detail once a client is over its limit. */
final class RateLimitFilter extends OncePerRequestFilter {

    private final ClientRateLimiter limiter;
    private final Counter rejected;

    RateLimitFilter(ClientRateLimiter limiter, Counter rejected) {
        this.limiter = limiter;
        this.rejected = rejected;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Behind a trusted proxy (server.forward-headers-strategy) this is the address the proxy forwarded.
        ClientRateLimiter.Decision decision = limiter.tryAcquire(request.getRemoteAddr());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        rejected.increment();
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        Problems.write(response, HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests from this client. Try again in %d s.".formatted(decision.retryAfterSeconds()));
    }
}
