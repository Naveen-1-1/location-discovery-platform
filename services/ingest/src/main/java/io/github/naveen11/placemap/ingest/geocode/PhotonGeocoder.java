package io.github.naveen11.placemap.ingest.geocode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Photon (ADR 0008): the public instance during development (ADR 0033), our own at deployment, same API. Asks for
 * house-level results inside the area's bounding box only.
 */
public final class PhotonGeocoder implements Geocoder {

    private static final String USER_AGENT =
            "place-discovery-map-ingest (+https://github.com/Naveen-1-1/place-discovery-map)";
    private static final JsonMapper JSON = new JsonMapper();
    private static final int CANDIDATES = 5;

    private final URI endpoint;
    private final String bbox;
    private final Duration timeout;
    private final HttpClient client;

    /** @param bounds {minLon, minLat, maxLon, maxLat} */
    public PhotonGeocoder(URI endpoint, double[] bounds, Duration timeout, HttpClient client) {
        this.endpoint = endpoint;
        this.bbox = String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", bounds[0], bounds[1], bounds[2], bounds[3]);
        this.timeout = timeout;
        this.client = client;
    }

    @Override
    public List<Candidate> search(String query) throws IOException, InterruptedException {
        URI uri = URI.create(endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + CANDIDATES + "&layer=house&bbox=" + bbox);
        var request = HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).timeout(timeout).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Never the query in the message: it is an address, and messages end up in logs.
            throw new IOException("Photon " + endpoint.getHost() + " returned HTTP " + response.statusCode());
        }
        return candidates(response.body());
    }

    static List<Candidate> candidates(String body) {
        var result = new ArrayList<Candidate>();
        for (JsonNode feature : JSON.readTree(body).path("features").values()) {
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            if (!coordinates.isArray() || coordinates.size() < 2) {
                continue;
            }
            JsonNode p = feature.path("properties");
            String label = Stream.of(text(p, "name"), join(text(p, "housenumber"), text(p, "street")), text(p, "city"))
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(", "));
            result.add(new Candidate(coordinates.get(0).asDouble(), coordinates.get(1).asDouble(), text(p, "housenumber"),
                    text(p, "street"), text(p, "postcode"), label));
        }
        return result;
    }

    private static String text(JsonNode properties, String field) {
        JsonNode value = properties.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static String join(String number, String street) {
        return number == null ? street : street == null ? number : number + " " + street;
    }
}
