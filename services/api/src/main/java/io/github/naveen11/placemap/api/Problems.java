package io.github.naveen11.placemap.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;

/**
 * Writes a refusal as {@code application/problem+json} from a servlet filter, which runs before Spring MVC and its
 * error handling. Uses the same converter as Spring MVC, so the body has the shape every other api error has.
 */
public final class Problems {

    private static final JacksonJsonHttpMessageConverter JSON = new JacksonJsonHttpMessageConverter();

    private Problems() {
    }

    /** Headers already set on {@code response} (Retry-After, Connection) are kept. */
    public static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        var out = new ServletServerHttpResponse(response);
        out.setStatusCode(status);
        JSON.write(ProblemDetail.forStatusAndDetail(status, detail), MediaType.APPLICATION_PROBLEM_JSON, out);
    }
}
