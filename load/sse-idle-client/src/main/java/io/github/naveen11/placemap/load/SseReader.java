package io.github.naveen11.placemap.load;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads one HTTP/1.1 response that carries Server-Sent Events: the status line, the headers, then events from a plain
 * or chunked body. Its own buffering, without locks, because thousands of these run at once.
 */
final class SseReader {

    /** One event: its {@code event:} name ({@code message} if none), its {@code data:} lines joined by newlines. */
    record Event(String name, String data) {}

    private final Lines head;
    private Lines body;
    private long retryMillis = -1;
    private long retryAfterSeconds = -1;

    SseReader(InputStream in) {
        this.head = new Lines(in);
    }

    /** Reads the status line and headers; returns the status code. Events follow only after a 200. */
    int readHead() throws IOException {
        String status = head.readLine();
        if (status == null) {
            throw new EOFException("closed before the status line");
        }
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("not an HTTP status line");
        }
        int code = Integer.parseInt(parts[1]);
        boolean chunked = false;
        for (String header = head.readLine(); header != null && !header.isEmpty(); header = head.readLine()) {
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Transfer-Encoding") && value.equalsIgnoreCase("chunked")) {
                chunked = true;
            } else if (name.equalsIgnoreCase("Retry-After") && value.matches("\\d+")) {
                retryAfterSeconds = Long.parseLong(value);
            }
        }
        body = chunked ? new Lines(new Chunked(head)) : head;
        return code;
    }

    /** The next event, or null at the end of the stream. Comments (heartbeats) and {@code retry:} are not events. */
    Event next() throws IOException {
        String name = null;
        StringBuilder data = null;
        for (String line = body.readLine(); line != null; line = body.readLine()) {
            if (line.isEmpty()) {
                if (name != null || data != null) {
                    return new Event(name == null ? "message" : name, data == null ? "" : data.toString());
                }
                continue;
            }
            if (line.charAt(0) == ':') {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            int start = colon < 0 ? line.length() : colon + 1;
            if (start < line.length() && line.charAt(start) == ' ') {
                start++;
            }
            String value = line.substring(start);
            switch (field) {
                case "event" -> name = value;
                case "data" -> data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                case "retry" -> {
                    if (value.matches("\\d+")) {
                        retryMillis = Long.parseLong(value);
                    }
                }
                default -> { } // id: the client does not resume from it
            }
        }
        return null;
    }

    /** The reconnection delay the server last asked for in the stream, or -1. */
    long retryMillis() {
        return retryMillis;
    }

    /** The {@code Retry-After} of a refusal, in seconds, or -1. */
    long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * When the server published the change, from a delta's {@code {"t":<ms>,...}}; -1 if the data does not start
     * that way (snapshots and status events).
     */
    static long publishedMillis(String data) {
        if (!data.startsWith("{\"t\":")) {
            return -1;
        }
        long t = 0;
        int i = 5;
        for (; i < data.length() && Character.isDigit(data.charAt(i)); i++) {
            t = t * 10 + (data.charAt(i) - '0');
        }
        return i > 5 ? t : -1;
    }

    /** Lines (LF or CRLF, UTF-8) and raw bytes from a stream, through one buffer. */
    static final class Lines {
        private final InputStream in;
        private final byte[] buffer = new byte[8192];
        private int pos;
        private int limit;
        private byte[] line = new byte[256];

        Lines(InputStream in) {
            this.in = in;
        }

        private boolean fill() throws IOException {
            int n = in.read(buffer, 0, buffer.length);
            if (n <= 0) {
                return false;
            }
            pos = 0;
            limit = n;
            return true;
        }

        /** The next line without its end, or null at the end of the stream. */
        String readLine() throws IOException {
            int length = 0;
            while (true) {
                if (pos == limit && !fill()) {
                    return length == 0 ? null : decode(length);
                }
                byte b = buffer[pos++];
                if (b == '\n') {
                    return decode(length > 0 && line[length - 1] == '\r' ? length - 1 : length);
                }
                if (length == line.length) {
                    line = Arrays.copyOf(line, length * 2);
                }
                line[length++] = b;
            }
        }

        private String decode(int length) {
            return new String(line, 0, length, StandardCharsets.UTF_8);
        }

        int read(byte[] target, int offset, int length) throws IOException {
            if (pos == limit && !fill()) {
                return -1;
            }
            int n = Math.min(length, limit - pos);
            System.arraycopy(buffer, pos, target, offset, n);
            pos += n;
            return n;
        }
    }

    /** The body of a chunked response: each chunk's size line, its bytes, and the CRLF after them. */
    static final class Chunked extends InputStream {
        private final Lines in;
        private long left;
        private boolean done;

        Chunked(Lines in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (done) {
                return -1;
            }
            if (left == 0) {
                String size = in.readLine();
                if (size == null) {
                    throw new EOFException("the stream ended inside a chunked body");
                }
                int semicolon = size.indexOf(';');
                left = Long.parseLong((semicolon < 0 ? size : size.substring(0, semicolon)).trim(), 16);
                if (left == 0) {
                    done = true;
                    return -1;
                }
            }
            int n = in.read(target, offset, (int) Math.min(length, left));
            if (n < 0) {
                throw new EOFException("the stream ended inside a chunk");
            }
            left -= n;
            if (left == 0) {
                in.readLine(); // the CRLF after the chunk's bytes
            }
            return n;
        }
    }
}
