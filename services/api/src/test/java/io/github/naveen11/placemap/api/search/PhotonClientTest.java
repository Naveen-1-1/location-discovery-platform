package io.github.naveen11.placemap.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Photon's answer as read, the request as sent, and the trace that never carries the typed text. */
class PhotonClientTest {

    private static final String BBOX = "-71.1913,42.2279,-70.8688,42.4182";

    @Test
    void asksInsideTheAreaNearTheMapAndReadsLabels() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://photon.test/api/");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith("https://photon.test/api/?q=1611%20Cambridge")))
                .andExpect(queryParam("bbox", BBOX.replace(",", "%2C")))
                .andExpect(queryParam("lat", "42.38"))  // rounded to about a kilometre
                .andExpect(queryParam("lon", "-71.11"))
                .andExpect(queryParam("limit", "5"))
                .andRespond(withSuccess("""
                        {"features": [
                         {"geometry": {"coordinates": [-71.1083678, 42.3752762]},
                          "properties": {"type": "house", "housenumber": "1611", "street": "Cambridge Street",
                                         "city": "Cambridge", "name": "Harvard Vanguard"}},
                         {"geometry": {"coordinates": [-71.10, 42.37]},
                          "properties": {"type": "street", "name": "Cambridge Street", "street": "Cambridge Street",
                                         "city": "Cambridge"}}
                        ]}""", MediaType.APPLICATION_JSON));
        var photon = new PhotonClient(builder.build(), BBOX);

        List<AddressMatch> matches = photon.search("1611 Cambridge St", 42.3812, -71.1149, 5);

        assertThat(matches).containsExactly(
                new AddressMatch("Harvard Vanguard, 1611 Cambridge Street, Cambridge", 42.3752762, -71.1083678, "house"),
                new AddressMatch("Cambridge Street, Cambridge", 42.37, -71.10, "street"));
        server.verify();
    }

    @Test
    void theTracedUrlLeavesOutWhatWasTyped() {
        var request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://photon.komoot.io/api/?q=12%20Elm%20St&lat=42.35&lon=-71.06"));
        var context = new ClientRequestObservationContext(request);

        assertThat(new PhotonClient.QueryFreeUrls().getHighCardinalityKeyValues(context).stream()
                .filter(kv -> kv.getKey().equals("http.url")).findFirst().orElseThrow().getValue())
                .isEqualTo("https://photon.komoot.io/api/");
    }
}
