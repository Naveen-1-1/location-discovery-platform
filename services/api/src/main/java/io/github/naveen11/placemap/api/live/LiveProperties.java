package io.github.naveen11.placemap.api.live;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.live.*}: how the api reaches live-hub.
 *
 * @param url     live-hub's base URL; empty turns live data off
 * @param timeout the most a nearby search waits for live data before answering without it
 */
@ConfigurationProperties("placemap.live")
public record LiveProperties(
        @DefaultValue("http://localhost:8081") String url,
        @DefaultValue("150ms") Duration timeout) {
}
