package io.github.naveen11.placemap.api.bodylimit;

import io.github.naveen11.placemap.api.Problems;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses request bodies before anything reads them. No api endpoint reads a body, so a request declaring
 * more than a few bytes gets 413, and one sending a body of unknown length (chunked) gets 411. The connection is then
 * closed, so the server neither reads nor swallows the rest of the body. Load scenario L9 measures it in Phase 8.
 */
final class BodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final Counter rejected;

    BodyLimitFilter(long maxBytes, Counter rejected) {
        this.maxBytes = maxBytes;
        this.rejected = rejected;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length > maxBytes) {
            refuse(response, HttpStatus.CONTENT_TOO_LARGE, "The api reads no request bodies over %d bytes."
                    .formatted(maxBytes));
            return;
        }
        if (length < 0 && chunked(request)) {
            refuse(response, HttpStatus.LENGTH_REQUIRED, "The api reads no request bodies of unknown length.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean chunked(HttpServletRequest request) {
        String encoding = request.getHeader(HttpHeaders.TRANSFER_ENCODING);
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");
    }

    private void refuse(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        rejected.increment();
        // Tomcat closes the connection after this response instead of reading the unread body to keep it open.
        response.setHeader(HttpHeaders.CONNECTION, "close");
        Problems.write(response, status, detail);
    }
}
