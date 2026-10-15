package io.github.naveen11.placemap.ingest.geocode;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The geocoder against a local Photon: what it asks for, what it reads back, and what it never logs. */
class PhotonGeocoderTest {

    @RegisterExtension
    static final WireMockExtension PHOTON = WireMockExtension.newInstance().options(options().dynamicPort()).build();

    private final PhotonGeocoder geocoder = new PhotonGeocoder(URI.create(PHOTON.baseUrl() + "/api/"),
            new double[] {-71.2, 42.2, -70.8, 42.4}, Duration.ofSeconds(5), HttpClient.newHttpClient());

    @Test
    void asksForHousesInTheAreaAndReadsTheCandidates() throws Exception {
        PHOTON.stubFor(get(urlPathEqualTo("/api/")).willReturn(okJson("""
                {"features": [
                  {"geometry": {"coordinates": [-71.12, 42.39]},
                   "properties": {"housenumber": "12", "street": "Elm Street", "city": "Somerville", "postcode": "02144"}},
                  {"geometry": {"coordinates": []}, "properties": {"name": "no position"}}
                ]}""")));

        List<Candidate> found = geocoder.search("12 Elm St, Somerville");

        assertThat(found).containsExactly(
                new Candidate(-71.12, 42.39, "12", "Elm Street", "02144", "12 Elm Street, Somerville"));
        PHOTON.verify(getRequestedFor(urlPathEqualTo("/api/"))
                .withQueryParam("q", equalTo("12 Elm St, Somerville"))
                .withQueryParam("layer", equalTo("house"))
                .withQueryParam("limit", equalTo("5"))
                .withQueryParam("bbox", equalTo("-71.20000,42.20000,-70.80000,42.40000")));
    }

    @Test
    void aFailureNeverIncludesTheAddress() {
        PHOTON.stubFor(get(urlPathEqualTo("/api/")).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> geocoder.search("12 Elm St, Somerville"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageNotContaining("Elm");
    }
}
