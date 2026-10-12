package io.github.naveen11.placemap.livehub.batch;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request bodies. The only body live-hub reads is the api's batch request, which streams its JSON
 * without a length. A request declaring more than the cap gets 413 before anything reads it, and the connection is
 * closed; a body of unknown length is read through a stream that fails once it passes the cap, which ends the request
 * with 400. Either way at most the cap plus one byte is ever read.
 */
final class BodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    BodyLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setHeader(HttpHeaders.CONNECTION, "close");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Content Too Large","status":413,\
                    "detail":"Request bodies are limited to %d bytes."}""".formatted(maxBytes));
            return;
        }
        // Requests without a body (the SSE stream among them) pass untouched.
        boolean body = request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
        chain.doFilter(body ? new Capped(request, maxBytes) : request, response);
    }

    /** The request with its body stream capped. */
    private static final class Capped extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        Capped(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CappedStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }
    }

    private static final class CappedStream extends ServletInputStream {

        private final ServletInputStream in;
        private final long maxBytes;
        private long read;

        CappedStream(ServletInputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            // Never asks for more than one byte past the cap, so an oversized body is not read any further.
            int n = in.read(buffer, offset, (int) Math.min(length, maxBytes - read + 1));
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > maxBytes) {
                throw new IOException("request body over " + maxBytes + " bytes");
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
