package io.github.naveen11.placemap.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.api.routing.RoutingException.Kind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/** Without ORS_API_KEY the api still starts, and every travel time falls back to straight-line distance. */
class RoutingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RoutingConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void withoutAKeyEveryCallFailsAsUnavailable() {
        runner.run(context -> {
            RoutingClient client = context.getBean(RoutingClient.class);
            assertThat(client.name()).isEqualTo("none");
            assertThatThrownBy(() -> client.travelTimes(OrsRoutingClientTest.COMMON,
                    List.of(OrsRoutingClientTest.FROG_POND), TravelMode.WALK))
                    .isInstanceOf(RoutingException.class)
                    .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.UNAVAILABLE);
        });
    }

    @Test
    void withAKeyItUsesOpenrouteservice() {
        runner.withPropertyValues("placemap.routing.ors.api-key=test-key")
                .run(context -> assertThat(context.getBean(RoutingClient.class)).isInstanceOf(OrsRoutingClient.class));
    }
}
