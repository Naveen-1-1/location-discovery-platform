package io.github.naveen11.placemap.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uber.h3core.H3Core;
import io.github.naveen11.placemap.api.TestH3;
import io.github.naveen11.placemap.api.routing.RoutingException.Kind;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * Without ORS_API_KEY the api still starts, and every travel time falls back to straight-line distance. The guard's
 * bulkhead, breaker and time limits come from application.yaml, as the app reads it.
 */
class RoutingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class,
                    BulkheadAutoConfiguration.class, CircuitBreakerAutoConfiguration.class,
                    TimeLimiterAutoConfiguration.class))
            .withUserConfiguration(RoutingConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(H3Core.class, () -> TestH3.CORE)
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

    @Test
    void theValhallaEngineNeedsNoKey() {
        runner.withPropertyValues("placemap.routing.engine=valhalla")
                .run(context -> {
                    assertThat(context.getBean(RoutingClient.class)).isInstanceOf(ValhallaRoutingClient.class);
                    assertThat(context.getBean(GuardedRouting.class).engine()).isEqualTo("valhalla");
                });
    }

    @Test
    void theGuardHasTheProductionSettings() {
        runner.run(context -> {
            CircuitBreakerConfig breaker = context.getBean(CircuitBreakerRegistry.class).circuitBreaker("routing")
                    .getCircuitBreakerConfig();
            CircuitBreakerConfig expected = FakeRoutingClient.BREAKER;
            assertThat(breaker.getSlidingWindowType()).isEqualTo(expected.getSlidingWindowType());
            assertThat(breaker.getSlidingWindowSize()).isEqualTo(expected.getSlidingWindowSize());
            assertThat(breaker.getMinimumNumberOfCalls()).isEqualTo(expected.getMinimumNumberOfCalls());
            assertThat(breaker.getFailureRateThreshold()).isEqualTo(expected.getFailureRateThreshold());
            assertThat(breaker.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);
            assertThat(breaker.getPermittedNumberOfCallsInHalfOpenState())
                    .isEqualTo(expected.getPermittedNumberOfCallsInHalfOpenState());
            assertThat(breaker.getIgnoreExceptionPredicate().test(new RoutingException(Kind.UNROUTABLE, "no path")))
                    .as("no route from here is an answer").isTrue();
            assertThat(breaker.getIgnoreExceptionPredicate().test(new RoutingException(Kind.TIMEOUT, "late"))).isFalse();

            BulkheadConfig bulkhead = context.getBean(BulkheadRegistry.class).bulkhead("routing").getBulkheadConfig();
            assertThat(bulkhead.getMaxConcurrentCalls()).isEqualTo(8);
            assertThat(bulkhead.getMaxWaitDuration()).as("full means refused at once").isZero();
        });
    }

    @Test
    void theTimeLimitsComeFromNearbyAndDirectionsSettings() {
        runner.withPropertyValues("placemap.nearby.travel-time-timeout=800ms").run(context -> {
            TimeLimiterRegistry limits = context.getBean(TimeLimiterRegistry.class);
            assertThat(limits.timeLimiter("travel-times").getTimeLimiterConfig().getTimeoutDuration())
                    .isEqualTo(Duration.ofMillis(800));
            assertThat(limits.timeLimiter("directions").getTimeLimiterConfig().getTimeoutDuration())
                    .isEqualTo(Duration.ofSeconds(5));
        });
    }
}
