package io.github.naveen11.placemap.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behaviour of {@link SnapshotPublisher}; the JCStress tests cover a subscriber joining during a publish. */
@Timeout(10)
class SnapshotPublisherTest {

    record Snap(long version, String label) {
    }

    @Test
    void publishMakesTheSnapshotCurrentThenDeliversChangesInOrder() {
        var publisher = new SnapshotPublisher<Snap, String, Integer>(new Snap(0, "empty"), Snap::version);
        var seen = new ArrayList<String>();
        publisher.subscribe((key, value) -> seen.add(publisher.current().label() + ":" + key + "=" + value));

        var changes = new LinkedHashMap<String, Integer>();
        changes.put("b", 2);
        changes.put("a", 1);
        publisher.publish(new Snap(1, "v1"), changes);

        assertThat(publisher.current()).isEqualTo(new Snap(1, "v1"));
        assertThat(seen).as("the snapshot is visible before any change is delivered").containsExactly("v1:b=2", "v1:a=1");
    }

    @Test
    void everySubscriberGetsEveryChangeUntilItUnsubscribes() {
        var publisher = new SnapshotPublisher<Snap, String, Integer>(new Snap(0, ""), Snap::version);
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        SnapshotPublisher.Subscriber<String, Integer> one = (k, v) -> first.add(k + v);
        SnapshotPublisher.Subscriber<String, Integer> two = (k, v) -> second.add(k + v);
        publisher.subscribe(one);
        publisher.subscribe(two);
        publisher.publish(new Snap(1, ""), Map.of("a", 1));
        publisher.unsubscribe(one);
        publisher.publish(new Snap(2, ""), Map.of("a", 2));

        assertThat(first).containsExactly("a1");
        assertThat(second).containsExactly("a1", "a2");
        assertThat(publisher.subscriberCount()).isEqualTo(1);
    }

    @Test
    void refusesAVersionThatIsNotNewer() {
        var publisher = new SnapshotPublisher<Snap, String, Integer>(new Snap(5, ""), Snap::version);
        assertThatThrownBy(() -> publisher.publish(new Snap(5, ""), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(new Snap(4, ""), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(publisher.current().version()).isEqualTo(5);
    }

    @Test
    void refusesASecondWriterThread() throws Exception {
        var publisher = new SnapshotPublisher<Snap, String, Integer>(new Snap(0, ""), Snap::version);
        publisher.publish(new Snap(1, ""), Map.of());

        var other = new CompletableFuture<Throwable>();
        Thread.ofPlatform().start(() -> {
            try {
                publisher.publish(new Snap(2, ""), Map.of());
                other.complete(null);
            } catch (RuntimeException e) {
                other.complete(e);
            }
        });
        assertThat(other.get(5, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class);
        assertThat(publisher.current().version()).isEqualTo(1);
    }
}
