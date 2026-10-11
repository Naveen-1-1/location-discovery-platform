package io.github.naveen11.placemap.livehub.batch;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.LiveProperties;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.TopicView;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /internal/live/batch}: the current live data for many items at once, read from the snapshot in memory
 * (ADR 0024). The api calls it for each nearby search, with a 150 ms deadline. Internal only: it is not routed by
 * the frontend's proxy or Caddy.
 *
 * <p>Request {@code {"keys": ["bikes:<station_id>", ...]}}. Response {@code {"version": V, "topics": {"bikes":
 * {"stale": false, "updated": ms}}, "items": {"bikes:<station_id>": {...}}}}; unknown keys are left out.
 */
@RestController
public class BatchController {

    /** The body: keys as {@code topic:id}. */
    public record BatchRequest(List<String> keys) {
    }

    private static final JsonMapper JSON = new JsonMapper();

    private final SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher;
    private final int maxKeys;

    public BatchController(SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher, LiveProperties properties) {
        this.publisher = publisher;
        this.maxKeys = properties.maxBatchKeys();
    }

    @PostMapping(path = "/internal/live/batch", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> batch(@RequestBody BatchRequest request) {
        List<String> keys = request.keys() == null ? List.of() : request.keys();
        if (keys.size() > maxKeys) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at most " + maxKeys + " keys");
        }
        LiveSnapshot snapshot = publisher.current(); // one snapshot for the whole answer, so it is consistent
        var topics = EnumSet.noneOf(Topic.class);
        var items = new LinkedHashMap<String, byte[]>();
        for (String key : keys) {
            int colon = key == null ? -1 : key.indexOf(':');
            if (colon < 1) {
                continue;
            }
            Optional<Topic> topic = Topic.fromWire(key.substring(0, colon));
            if (topic.isEmpty()) {
                continue;
            }
            topics.add(topic.get());
            byte[] item = snapshot.topic(topic.get()).items().get(key.substring(colon + 1));
            if (item != null) {
                items.put(key, item);
            }
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json(snapshot, topics, items));
    }

    private static byte[] json(LiveSnapshot snapshot, EnumSet<Topic> topics, Map<String, byte[]> items) {
        var out = new ByteArrayOutputStream(64 + items.size() * 200);
        write(out, "{\"version\":" + snapshot.version() + ",\"topics\":{");
        boolean first = true;
        for (Topic topic : topics) {
            TopicView view = snapshot.topic(topic);
            write(out, (first ? "" : ",") + "\"" + topic.wire() + "\":{\"stale\":" + view.stale() + ",\"updated\":"
                    + (view.updatedMillis() == 0 ? "null" : view.updatedMillis()) + "}");
            first = false;
        }
        write(out, "},\"items\":{");
        first = true;
        for (Map.Entry<String, byte[]> item : items.entrySet()) {
            write(out, (first ? "" : ",") + JSON.writeValueAsString(item.getKey()) + ":");
            out.writeBytes(item.getValue());
            first = false;
        }
        write(out, "}}");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
