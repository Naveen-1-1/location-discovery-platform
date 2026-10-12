package io.github.naveen11.placemap.livehub;

import io.github.naveen11.placemap.livehub.sse.ConnectionMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code placemap.live.*}, defaults from the plan (showcase 2) and ADR 0018, 0023 and 0041.
 *
 * @param tick                    how often the snapshot writer publishes, at most
 * @param queueCapacity           feed updates waiting for the writer, at most
 * @param submitWait              how long a feed waits for room in that queue before its update is dropped
 * @param staleAfterTtls          a topic is stale after this many of its feed's ttls without an answer
 * @param connectionExecutor      {@code virtual}: a thread per connection; {@code pool}: a fixed pool takes turns
 * @param poolSize                threads in the pool, in pool mode
 * @param mailboxCapacity         keys waiting per client before its mailbox overflows into a resync
 * @param maxConnections          open streams in total; more answer 503
 * @param maxConnectionsPerClient open streams per client address; more answer 429
 * @param evictAfter              a client whose write or oldest waiting frame is older than this is disconnected
 * @param heartbeat               a comment is sent after this long without any other write
 * @param watchdogTick            how often the watchdog checks every session
 * @param retryMin                the shortest reconnect delay a client is told; each gets a random one up to retryMax
 * @param maxBatchKeys            items one {@code /internal/live/batch} call may ask for
 * @param maxBody                 the largest request body read (ADR 0044); 500 batch keys take about 25 KB
 */
@ConfigurationProperties("placemap.live")
public record LiveProperties(
        @DefaultValue("250ms") Duration tick,
        @DefaultValue("10000") int queueCapacity,
        @DefaultValue("1s") Duration submitWait,
        @DefaultValue("3") int staleAfterTtls,
        @DefaultValue("virtual") ConnectionMode connectionExecutor,
        @DefaultValue("200") int poolSize,
        @DefaultValue("512") int mailboxCapacity,
        @DefaultValue("10000") int maxConnections,
        @DefaultValue("8") int maxConnectionsPerClient,
        @DefaultValue("10s") Duration evictAfter,
        @DefaultValue("15s") Duration heartbeat,
        @DefaultValue("1s") Duration watchdogTick,
        @DefaultValue("2s") Duration retryMin,
        @DefaultValue("7s") Duration retryMax,
        @DefaultValue("500") int maxBatchKeys,
        @DefaultValue("64KB") DataSize maxBody,
        @DefaultValue Bluebikes bluebikes,
        @DefaultValue Mbta mbta) {

    /**
     * The Bluebikes {@code station_status} feed.
     *
     * @param minInterval polls never come closer together than this, whatever the feed's ttl says
     * @param maxInterval nor further apart than this
     */
    public record Bluebikes(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("https://gbfs.bluebikes.com/gbfs/en/station_status.json") String url,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration timeout,
            @DefaultValue("15s") Duration minInterval,
            @DefaultValue("5m") Duration maxInterval) {
    }

    /**
     * MBTA: the V3 streams when there is an API key, the keyless GTFS-Realtime JSON files while a stream is down or
     * there is no key, and the static GTFS zip both are read with (ADR 0041).
     *
     * @param apiKey           MBTA V3 key from {@code MBTA_API_KEY}; empty means the open files only
     * @param streamTick       how often a connected stream counts as an answer; its topic is stale after 3 of these
     * @param vehiclesIdle     a vehicles stream silent this long is reconnected (vehicles move every few seconds)
     * @param alertsIdle       the same for alerts, which can be quiet for long stretches
     * @param departuresDemand trip updates are polled only this long after someone last asked for departures
     */
    public record Mbta(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String apiKey,
            @DefaultValue("https://api-v3.mbta.com") String v3Url,
            @DefaultValue("15s") Duration streamTick,
            @DefaultValue("1m") Duration vehiclesIdle,
            @DefaultValue("30m") Duration alertsIdle,
            @DefaultValue("https://cdn.mbta.com/realtime/") String realtimeUrl,
            @DefaultValue("https://cdn.mbta.com/MBTA_GTFS.zip") String gtfsUrl,
            @DefaultValue("30s") Duration alertsInterval,
            @DefaultValue("15s") Duration vehiclesInterval,
            @DefaultValue("15s") Duration departuresInterval,
            @DefaultValue("2m") Duration departuresDemand,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("20s") Duration timeout) {
    }
}
