package io.github.naveen11.placemap.livehub.batch;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request declaring a body over the cap with 413, before anything reads it, and closes the connection. A
 * body of unknown length gets through: the only body live-hub reads is the api's batch request, which streams its
 * JSON without a length, and {@link BatchController}'s parser stops reading once it passes the cap.
 */
final class BodyLimitFilter extends OncePerRequestFilter {

    private static final JacksonJsonHttpMessageConverter JSON = new JacksonJsonHttpMessageConverter();

    private final long maxBytes;

    BodyLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() <= maxBytes) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.CONNECTION, "close");
        var out = new ServletServerHttpResponse(response);
        out.setStatusCode(HttpStatus.CONTENT_TOO_LARGE);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "Request bodies are limited to %d bytes.".formatted(maxBytes));
        JSON.write(problem, MediaType.APPLICATION_PROBLEM_JSON, out);
    }
}
