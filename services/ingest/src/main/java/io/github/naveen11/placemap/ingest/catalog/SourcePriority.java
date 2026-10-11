package io.github.naveen11.placemap.ingest.catalog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Gate G1 (ADR 0032), from {@code data/source-priority.yaml}: which sources may create a place in each category,
 * which only enrich, and the Overture confidence rule. Also ranks sources per category, which decides whose name,
 * address and phone a merged place shows. Immutable.
 */
public final class SourcePriority {

    public static final String OVERTURE = "overture_places";
    public static final String OSM = "osm_geofabrik_massachusetts";

    // The priority file names the two open sources by short names.
    private static final Map<String, String> ALIASES = Map.of("overture", OVERTURE, "osm", OSM);

    private record CategoryRule(List<String> existence, List<String> enrich, List<String> overtureCorroborators) {
    }

    /** One row for the completion step's temporary ranking table. */
    public record Role(String category, String source, int rank, boolean existence) {
    }

    private final Map<String, CategoryRule> categories;
    private final double minConfidenceAlone;
    private final double corroborationRadiusMeters;
    private final double minCorroborationNameSimilarity;

    private SourcePriority(Map<String, CategoryRule> categories, double minConfidenceAlone,
            double corroborationRadiusMeters, double minCorroborationNameSimilarity) {
        this.categories = categories;
        this.minConfidenceAlone = minConfidenceAlone;
        this.corroborationRadiusMeters = corroborationRadiusMeters;
        this.minCorroborationNameSimilarity = minCorroborationNameSimilarity;
    }

    public static SourcePriority load(Path file) {
        JsonNode root = new YAMLMapper().readTree(file);
        JsonNode overture = root.required("overture_rule");
        JsonNode corroboration = overture.required("below_threshold_needs_corroboration");
        var categories = new LinkedHashMap<String, CategoryRule>();
        for (Map.Entry<String, JsonNode> entry : root.required("categories").properties()) {
            JsonNode rule = entry.getValue();
            categories.put(entry.getKey(), new CategoryRule(sources(rule.path("existence")), sources(rule.path("enrich")),
                    sources(rule.path("overture_requires_corroboration_by"))));
        }
        return new SourcePriority(Map.copyOf(categories), overture.required("min_confidence_alone").asDouble(),
                corroboration.required("radius_m").asDouble(), corroboration.required("min_name_similarity").asDouble());
    }

    private static List<String> sources(JsonNode list) {
        return list.values().stream().map(JsonNode::asString).map(s -> ALIASES.getOrDefault(s, s)).toList();
    }

    /** Whether records of this source may decide that a place in this category exists. */
    public boolean isExistence(String category, String source) {
        CategoryRule rule = categories.get(category);
        return rule != null && rule.existence().contains(source);
    }

    /**
     * Whether an unmatched record may create a place on its own. Enrichment sources never may. Overture needs
     * confidence at or above the threshold, and never may where the category demands corroboration (pharmacies).
     */
    public boolean mayCreateAlone(String category, String source, Double confidence) {
        if (category == null || !isExistence(category, source)) {
            return false;
        }
        if (OVERTURE.equals(source)) {
            return categories.get(category).overtureCorroborators().isEmpty()
                    && confidence != null && confidence >= minConfidenceAlone;
        }
        return true;
    }

    /** An Overture record that may not stand alone but may join a place another existence source confirms. */
    public boolean needsCorroboration(String category, String source, Double confidence) {
        return OVERTURE.equals(source) && category != null && isExistence(category, source)
                && !mayCreateAlone(category, source, confidence);
    }

    /** Sources whose presence on a nearby place corroborates this source's record. */
    public Set<String> corroborators(String category, String source) {
        CategoryRule rule = categories.get(category);
        if (rule == null) {
            return Set.of();
        }
        var sources = new LinkedHashSet<>(OVERTURE.equals(source) && !rule.overtureCorroborators().isEmpty()
                ? rule.overtureCorroborators() : rule.existence());
        sources.remove(source);
        return sources;
    }

    public double corroborationRadiusMeters() {
        return corroborationRadiusMeters;
    }

    public double minCorroborationNameSimilarity() {
        return minCorroborationNameSimilarity;
    }

    /** Every (category, source) pair with its rank: existence sources first, in file order, then enrichment ones. */
    public List<Role> roles() {
        var roles = new ArrayList<Role>();
        categories.forEach((category, rule) -> {
            for (int i = 0; i < rule.existence().size(); i++) {
                roles.add(new Role(category, rule.existence().get(i), i, true));
            }
            for (int i = 0; i < rule.enrich().size(); i++) {
                if (!rule.existence().contains(rule.enrich().get(i))) {
                    roles.add(new Role(category, rule.enrich().get(i), 100 + i, false));
                }
            }
        });
        return roles;
    }
}
