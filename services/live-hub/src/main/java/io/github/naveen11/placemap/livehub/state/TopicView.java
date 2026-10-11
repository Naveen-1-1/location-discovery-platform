package io.github.naveen11.placemap.livehub.state;

import java.util.Map;

/**
 * One topic inside an immutable {@link LiveSnapshot}.
 *
 * @param items         item id → the item's JSON, sorted by id; never modified after publication
 * @param stale         whether the feed has been silent for longer than 3× its ttl, or has never answered
 * @param updatedMillis when the feed last answered (epoch milliseconds), or 0 if never
 * @param snapshotEvent the {@code snapshot} event for this topic without its {@code id:} line, built once
 */
public record TopicView(Topic topic, Map<String, byte[]> items, boolean stale, long updatedMillis,
        byte[] snapshotEvent) {
}
