package io.github.naveen11.placemap.api.search;

import io.github.naveen11.placemap.api.Deadline;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/search?q=…&lat=…&lon=…}: places by name and addresses, for the search box. */
@RestController
@RequestMapping("/api")
@Validated
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchService search;
    private final SearchProperties properties;

    public SearchController(SearchService search, SearchProperties properties) {
        this.search = search;
        this.properties = properties;
    }

    /** @param lat where the map is, so nearer matches come first; Boston Common by default */
    @GetMapping("/search")
    public SearchService.SearchResult search(
            @RequestParam @Size(min = 1, max = 100) String q,
            @RequestParam(defaultValue = "42.355") @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam(defaultValue = "-71.0656") @DecimalMin("-180") @DecimalMax("180") double lon) {
        SearchService.SearchResult result;
        try {
            result = search.search(q, lat, lon, Deadline.after(properties.deadline()));
        } catch (BulkheadFullException e) {
            // As nearby does (ADR 0026): the database has all the searches it can take.
            var error = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, e);
            error.setDetail("The database is busy. Try again in a moment.");
            error.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
            throw error;
        }
        // Never the text or the coordinates (privacy): only how much was typed and what came back.
        log.atInfo()
                .addKeyValue("length", q.strip().length())
                .addKeyValue("places", result.places().size())
                .addKeyValue("addresses", result.addresses().size())
                .addKeyValue("address_status", result.addressStatus())
                .log("search");
        return result;
    }
}
