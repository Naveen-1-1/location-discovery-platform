package io.github.naveen11.placemap.livehub.state;

import java.util.Map;

/**
 * Everything live-hub knows at one moment, immutable once published (ADR 0023). The snapshot writer builds a new one
 * at most every tick; a topic that did not change keeps its previous {@link TopicView}.
 *
 * @param version          rises by one per published snapshot; the SSE event id
 * @param publishedAtMillis when it was published (epoch milliseconds)
 */
public record LiveSnapshot(long version, long publishedAtMillis, Map<Topic, TopicView> topics) {

    public TopicView topic(Topic topic) {
        return topics.get(topic);
    }
}
