package io.github.naveen11.placemap.livehub.state;

import java.time.Duration;
import java.util.Map;

/** What a feed hands to the snapshot writer. Feeds never touch live state themselves (single writer, ADR 0023). */
public sealed interface FeedUpdate {

    Topic topic();

    /**
     * The feed's whole current state, as a poll returns it. Items missing from it are gone.
     *
     * @param items         item id → the item's JSON object; equal bytes mean an unchanged item
     * @param fetchedAtMillis when the feed answered
     * @param ttl           how long the publisher says the data stays current
     */
    record Replace(Topic topic, Map<String, byte[]> items, long fetchedAtMillis, Duration ttl) implements FeedUpdate {
    }

    /** The feed did not answer usefully. The topic goes stale once this has lasted 3× its ttl. */
    record Failed(Topic topic, String reason, long atMillis) implements FeedUpdate {
    }
}
