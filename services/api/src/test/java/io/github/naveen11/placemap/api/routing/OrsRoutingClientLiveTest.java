package io.github.naveen11.placemap.api.routing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

/**
 * The routing contract against the real openrouteservice API, with the key from ORS_API_KEY or .env. It spends 6 of
 * the day's free calls (3 of them matrix calls), so it only runs when asked:
 * {@code ./mvnw -pl services/api -am verify -Dplacemap.live-routing=true}.
 */
@EnabledIfSystemProperty(named = "placemap.live-routing", matches = "true")
class OrsRoutingClientLiveTest extends RoutingClientContract {

    private final RoutingClient client = create();

    @Override
    RoutingClient client() {
        return client;
    }

    private static RoutingClient create() {
        // Over the internet and outside any nearby deadline, so every call gets the same 5 s.
        var ors = new RoutingProperties.Ors(URI.create("https://api.openrouteservice.org"), apiKey(),
                Duration.ofSeconds(5), Duration.ofSeconds(5), 350);
        RestClient rest = OrsRoutingClient.configure(RestClient.builder(), ors)
                .requestFactory(RoutingConfig.jdk(ors.timeout()))
                .build();
        return new OrsRoutingClient(rest, rest, ors.maxSnapMeters());
    }

    private static String apiKey() {
        String key = System.getenv("ORS_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }
        try {
            return Files.readAllLines(Path.of("../../.env")).stream()
                    .filter(line -> line.startsWith("ORS_API_KEY="))
                    .map(line -> line.substring("ORS_API_KEY=".length()).split(" {2}#")[0].strip())
                    .map(value -> value.replaceAll("^[\"']|[\"']$", ""))
                    .findFirst()
                    .filter(k -> !k.isBlank())
                    .orElseThrow(() -> new IllegalStateException("ORS_API_KEY is not set in the environment or .env"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
