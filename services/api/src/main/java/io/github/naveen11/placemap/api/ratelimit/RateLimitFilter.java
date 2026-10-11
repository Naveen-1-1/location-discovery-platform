package io.github.naveen11.placemap.api.ratelimit;

import io.micrometer.core.instrument.Counter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 429 with Retry-After once a client is over its limit (ADR 0026). It runs before Spring MVC, so it writes
 * the problem-details body itself, in the same shape the rest of the API uses.
 */
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
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getWriter().write("""
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"Too many requests from this client. Try again in %d s."}""".formatted(
                decision.retryAfterSeconds()));
    }
}
