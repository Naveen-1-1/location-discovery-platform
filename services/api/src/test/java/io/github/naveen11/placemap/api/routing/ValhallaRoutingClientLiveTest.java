package io.github.naveen11.placemap.api.routing;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

/**
 * The routing contract against our own Valhalla with Massachusetts tiles. It needs one running, so it only runs when
 * given its address: {@code tools/build-valhalla-tiles.sh --serve}, then
 * {@code ./mvnw -pl services/api -am verify -Dplacemap.valhalla-url=http://localhost:8002}.
 */
@EnabledIfSystemProperty(named = "placemap.valhalla-url", matches = ".+")
class ValhallaRoutingClientLiveTest extends RoutingClientContract {

    private final RoutingClient client = create();

    @Override
    RoutingClient client() {
        return client;
    }

    private static RoutingClient create() {
        // Outside any nearby deadline, and the first calls read tiles from disk, so every call gets the same 5 s.
        var valhalla = new RoutingProperties.Valhalla(URI.create(System.getProperty("placemap.valhalla-url")),
                Duration.ofSeconds(5), Duration.ofSeconds(5), 350);
        RestClient rest = ValhallaRoutingClient.configure(RestClient.builder(), valhalla)
                .requestFactory(RoutingConfig.jdk(valhalla.timeout()))
                .build();
        return new ValhallaRoutingClient(rest, rest, valhalla.maxSnapMeters());
    }
}
