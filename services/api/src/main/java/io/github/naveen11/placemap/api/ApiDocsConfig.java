package io.github.naveen11.placemap.api;

import io.swagger.v3.oas.models.media.Schema;
import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * How springdoc describes the api (/v3/api-docs, on with {@code PLACEMAP_API_DOCS=true}), which the frontend's
 * response types are generated from:
 *
 * <ul>
 *   <li>a field marked {@link Nullable} may be null (Jackson writes every field, so the rest are always there);
 *   <li>a {@link JsonNode} (live-hub's data, a GeoJSON geometry) is any JSON, not Jackson's own API; the frontend
 *       describes it with its own types.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class ApiDocsConfig {

    static {
        SpringDocUtils.getConfig().replaceWithSchema(JsonNode.class, new Schema<>()); // any JSON
    }

    @Bean
    PropertyCustomizer nullableFields() {
        return (property, type) -> {
            boolean nullable = type.getCtxAnnotations() != null
                    && Arrays.stream(type.getCtxAnnotations()).anyMatch(Nullable.class::isInstance);
            if (!nullable) {
                return property;
            }
            if (property.get$ref() != null || property.getEnum() != null) { // "this, or null"
                var self = property.get$ref() != null ? new Schema<>().$ref(property.get$ref()) : property;
                return new Schema<>().oneOf(List.of(self, new Schema<>().types(Set.of("null"))));
            }
            var types = new LinkedHashSet<String>(property.getTypes() != null ? property.getTypes() : List.of());
            if (types.isEmpty() && property.getType() != null) {
                types.add(property.getType());
            }
            if (types.isEmpty()) {
                return property; // any JSON, null included
            }
            types.add("null");
            property.setTypes(types);
            return property;
        };
    }
}
