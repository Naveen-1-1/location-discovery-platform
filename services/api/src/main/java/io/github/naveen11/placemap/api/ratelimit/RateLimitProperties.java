package io.github.naveen11.placemap.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.rate-limit.*}, defaults from ADR 0026.
 *
 * @param perSecond requests a client may make per second, on average
 * @param burst requests a client may make at once after being idle
 * @param maxClients clients tracked at once; the least recently seen are forgotten first
 */
@ConfigurationProperties("placemap.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int perSecond,
        @DefaultValue("20") int burst,
        @DefaultValue("100000") int maxClients) {}
