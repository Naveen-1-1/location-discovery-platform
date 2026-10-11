package io.github.naveen11.placemap.livehub;

import io.github.naveen11.placemap.livehub.state.Topic;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * live-hub's metrics, all under {@code placemap.live}. No label has more than a handful of values: never a client,
 * dock or stop id, to stay far under Grafana Cloud's free series limit (plan, risk 8).
 */
public class LiveMetrics {

    private final MeterRegistry registry;
    private final Counter coalesced;
    private final Counter overflows;
    private final Counter snapshotsSent;
    private final Timer publish;
    private final Timer fanoutLag;

    public LiveMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.coalesced = Counter.builder("placemap.live.frames.coalesced")
                .description("Changes that replaced an older one still waiting for a client")
                .register(registry);
        this.overflows = Counter.builder("placemap.live.mailbox.overflows")
                .description("Client mailboxes that filled up and were cleared for a resync")
                .register(registry);
        this.snapshotsSent = Counter.builder("placemap.live.snapshots.sent")
                .description("Full snapshots written to clients: one per connection plus one per overflow")
                .register(registry);
        this.publish = Timer.builder("placemap.live.publish")
                .description("Building a snapshot and handing its changes to every client mailbox")
                .publishPercentileHistogram()
                .register(registry);
        this.fanoutLag = Timer.builder("placemap.live.fanout.lag")
                .description("From a snapshot's publication until a change from it was written to a client")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void connection(String result) {
        registry.counter("placemap.live.connections", "result", result).increment();
    }

    public void sessionClosed(String reason) {
        registry.counter("placemap.live.sessions.closed", "reason", reason).increment();
    }

    public void coalesced() {
        coalesced.increment();
    }

    public void overflow() {
        overflows.increment();
    }

    public void snapshotSent() {
        snapshotsSent.increment();
    }

    public void updateDropped(Topic topic) {
        registry.counter("placemap.live.updates.dropped", "topic", topic.wire()).increment();
    }

    public void poll(Topic topic, String outcome) {
        registry.counter("placemap.live.feed.polls", "topic", topic.wire(), "outcome", outcome).increment();
    }

    public void publishTook(long nanos) {
        publish.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void fanoutLag(long nanos) {
        fanoutLag.record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    public <T> void gauge(String name, String description, T state, ToDoubleFunction<T> value) {
        Gauge.builder(name, state, value).description(description).register(registry);
    }

    public void topicGauge(String name, String description, Topic topic, Supplier<Number> value) {
        Gauge.builder(name, value).description(description).tag("topic", topic.wire()).register(registry);
    }
}
