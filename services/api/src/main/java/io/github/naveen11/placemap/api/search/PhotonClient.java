package io.github.naveen11.placemap.api.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.common.KeyValue;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.web.client.RestClient;

/**
 * Address search on Photon (ADR 0008): results inside the four towns' bounding box, nearest to the map first.
 *
 * <p>Privacy: what people type is an address, so it never reaches logs or traces. The request is traced, but the
 * span's URL is recorded without its query string ({@link QueryFreeUrls}); errors name the host only. The location
 * hint sent to Photon is rounded to about a kilometre.
 */
public final class PhotonClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Answer(List<Feature> features) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Feature(Geometry geometry, Properties properties) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Geometry(List<Double> coordinates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Properties(String name, String housenumber, String street, String city, String type) {
    }

    private final RestClient http;
    private final String bbox;

    /** @param bbox "minLon,minLat,maxLon,maxLat" */
    public PhotonClient(RestClient http, String bbox) {
        this.http = http;
        this.bbox = bbox;
    }

    public List<AddressMatch> search(String query, double nearLat, double nearLon, int limit) {
        Answer answer = http.get()
                .uri("?q={q}&limit={limit}&bbox={bbox}&lat={lat}&lon={lon}&lang=en", query, limit, bbox,
                        String.format(Locale.ROOT, "%.2f", nearLat), String.format(Locale.ROOT, "%.2f", nearLon))
                .retrieve()
                .body(Answer.class);
        var matches = new ArrayList<AddressMatch>();
        if (answer == null || answer.features() == null) {
            return matches;
        }
        for (Feature feature : answer.features()) {
            if (feature.geometry() == null || feature.geometry().coordinates() == null
                    || feature.geometry().coordinates().size() < 2 || feature.properties() == null) {
                continue;
            }
            Properties p = feature.properties();
            String street = p.housenumber() != null && p.street() != null ? p.housenumber() + " " + p.street() : p.street();
            String label = Stream.of(p.name(), street, p.city())
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .collect(Collectors.joining(", "));
            if (!label.isEmpty()) {
                matches.add(new AddressMatch(label, feature.geometry().coordinates().get(1),
                        feature.geometry().coordinates().get(0), p.type() == null ? "place" : p.type()));
            }
        }
        return matches;
    }

    /** The default client observation, with the URL recorded without its query string. */
    public static final class QueryFreeUrls extends DefaultClientRequestObservationConvention {

        @Override
        protected KeyValue requestUri(ClientRequestObservationContext context) {
            if (context.getCarrier() == null) {
                return super.requestUri(context);
            }
            URI uri = context.getCarrier().getURI();
            return KeyValue.of("http.url", uri.getScheme() + "://" + uri.getHost() + uri.getPath());
        }
    }
}
