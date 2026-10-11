package io.github.naveen11.placemap.livehub.departures;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.TopicView;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /live/departures?stop=<stop_id>}: the next MBTA departures at one stop or station, from the snapshot in
 * memory. Departures are not streamed (every stop together is about 2 MB), so the map asks for the stop it shows
 * while it shows it.
 *
 * <p>Response {@code {"stop": "place-harsq", "stale": false, "updated": ms, "departures": {"id": ..., "next": [...]}}};
 * {@code departures} is null when the stop has none coming. The first request after a quiet spell starts the feed, and
 * says {@code stale} until its first answer lands a few seconds later.
 */
@RestController
public class DeparturesController {

    private static final Pattern STOP_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final JsonMapper JSON = new JsonMapper();

    private final SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher;
    private final Demand demand;
    private final PollNow pollNow;

    public DeparturesController(SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher, Demand demand,
            PollNow pollNow) {
        this.publisher = publisher;
        this.demand = demand;
        this.pollNow = pollNow;
    }

    @GetMapping(path = "/live/departures", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> departures(@RequestParam String stop) {
        if (!STOP_ID.matcher(stop).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a GTFS stop id");
        }
        if (demand.mark()) {
            pollNow.run(); // nobody was looking: the feed was idle, so fetch now instead of at its next trigger
        }
        TopicView view = publisher.current().topic(Topic.DEPARTURES);
        byte[] item = view.items().get(stop);
        String body = "{\"stop\":" + JSON.writeValueAsString(stop) + ",\"stale\":" + view.stale() + ",\"updated\":"
                + (view.updatedMillis() == 0 ? "null" : view.updatedMillis()) + ",\"departures\":"
                + (item == null ? "null" : new String(item, StandardCharsets.UTF_8)) + "}";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body.getBytes(StandardCharsets.UTF_8));
    }
}
