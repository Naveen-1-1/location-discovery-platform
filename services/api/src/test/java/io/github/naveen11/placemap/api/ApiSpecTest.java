package io.github.naveen11.placemap.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.uber.h3core.H3Core;
import io.github.naveen11.placemap.api.directions.DirectionsController;
import io.github.naveen11.placemap.api.directions.DirectionsProperties;
import io.github.naveen11.placemap.api.directions.DirectionsService;
import io.github.naveen11.placemap.api.live.LiveHubClient;
import io.github.naveen11.placemap.api.places.CategoryCatalog;
import io.github.naveen11.placemap.api.places.NearbyController;
import io.github.naveen11.placemap.api.places.NearbyProperties;
import io.github.naveen11.placemap.api.places.NearbyService;
import io.github.naveen11.placemap.api.places.OriginSnapper;
import io.github.naveen11.placemap.api.places.PlaceController;
import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.naveen11.placemap.api.search.SearchController;
import io.github.naveen11.placemap.api.search.SearchProperties;
import io.github.naveen11.placemap.api.search.SearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The api's OpenAPI description, which the frontend's response types are generated from ({@code npm run types} in
 * frontend/). Fails while frontend/openapi.json differs from what the controllers describe, so a changed response
 * shows up as a diff; {@code -Dplacemap.api-spec.write=true} rewrites the file.
 */
@WebMvcTest(controllers = {NearbyController.class, PlaceController.class, SearchController.class,
        DirectionsController.class}, properties = "springdoc.api-docs.enabled=true")
@Import({ApiDocsConfig.class, CategoryCatalog.class, OriginSnapper.class, ApiSpecTest.Settings.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class})
class ApiSpecTest {

    static final Path SPEC = Path.of("../../frontend/openapi.json");

    @TestConfiguration
    @EnableConfigurationProperties({NearbyProperties.class, SearchProperties.class, DirectionsProperties.class})
    static class Settings {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        H3Core h3Core() {
            return TestH3.CORE;
        }
    }

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    PlaceRepository places;

    @MockitoBean
    NearbyService nearby;

    @MockitoBean
    LiveHubClient live;

    @MockitoBean
    SearchService search;

    @MockitoBean
    DirectionsService directions;

    @Test
    void theFrontendsCopyOfTheApiDescriptionIsCurrent() throws IOException {
        var result = mvc.get().uri("/v3/api-docs").exchange();
        assertThat(result).hasStatusOk();
        String spec = result.getResponse().getContentAsString(StandardCharsets.UTF_8) + "\n";

        if (Boolean.getBoolean("placemap.api-spec.write")) {
            Files.writeString(SPEC, spec);
        }
        assertThat(Files.exists(SPEC)).as("%s exists (write it with -Dplacemap.api-spec.write=true)", SPEC).isTrue();
        assertThat(Files.readString(SPEC))
                .as("%s is current: rewrite it with -Dplacemap.api-spec.write=true, then run npm run types in frontend/",
                        SPEC)
                .isEqualTo(spec);
    }
}
