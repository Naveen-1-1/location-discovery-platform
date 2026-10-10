package io.github.naveen11.placemap.api.drills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberate failures for alerting drills ("does a 5xx burst page me?"). Only exists when placemap.drills.enabled is
 * true, which is off by default and never set outside local development.
 */
@RestController
@ConditionalOnBooleanProperty("placemap.drills.enabled")
class DrillController {

    private static final Logger log = LoggerFactory.getLogger(DrillController.class);

    @GetMapping("/api/_drills/error")
    String error() {
        log.error("Alert drill: deliberately failing this request");
        throw new IllegalStateException("alert drill");
    }
}
