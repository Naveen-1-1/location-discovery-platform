package io.github.naveen11.placemap.ingest.catalog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Maps a source's own classification onto the app categories, as written in {@code data/categories.yaml}
 * (ADR 0032). Rules are data, not code. Categories are tried in file order, and the first match wins. Immutable, so
 * parse workers share one instance.
 *
 * <ul>
 *   <li><b>Overture and OSM</b> ({@code overture:}, {@code osm:}): the listed fields are alternatives, so a row
 *       matches if <em>any</em> of them has a listed value (OSM {@code amenity=pharmacy} or
 *       {@code healthcare=pharmacy}).
 *   <li><b>Official sources</b> ({@code official: {source_id: ...}}): the listed fields are filters, so a row matches
 *       only if <em>every</em> one has a listed value. An empty rule ({@code {}}) matches every row.
 * </ul>
 */
public final class CategoryRules {

    static final String OVERTURE = "overture";
    static final String OSM = "osm";

    private record FieldRule(String field, List<String> values) {

        boolean matches(Function<String, String> row) {
            String value = row.apply(field);
            return value != null && values.contains(value);
        }
    }

    /** @param scope "overture", "osm", or an official source id */
    private record Rule(String category, String scope, boolean matchAll, List<FieldRule> fields) {

        boolean matches(Function<String, String> row) {
            return matchAll ? fields.stream().allMatch(f -> f.matches(row)) : fields.stream().anyMatch(f -> f.matches(row));
        }
    }

    private final List<Rule> rules;

    private CategoryRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static CategoryRules load(Path file) {
        JsonNode categories = new YAMLMapper().readTree(file).required("categories");
        var rules = new ArrayList<Rule>();
        for (Map.Entry<String, JsonNode> category : categories.properties()) {
            JsonNode body = category.getValue();
            for (String open : List.of(OVERTURE, OSM)) {
                if (body.has(open)) {
                    rules.add(new Rule(category.getKey(), open, false, fields(body.get(open))));
                }
            }
            for (Map.Entry<String, JsonNode> source : body.path("official").properties()) {
                rules.add(new Rule(category.getKey(), source.getKey(), true, fields(source.getValue())));
            }
        }
        return new CategoryRules(List.copyOf(rules));
    }

    private static List<FieldRule> fields(JsonNode rule) {
        var fields = new ArrayList<FieldRule>();
        for (Map.Entry<String, JsonNode> field : rule.properties()) {
            // YAML may write codes as numbers (location_type: [0, 1]); rows hold text.
            fields.add(new FieldRule(field.getKey(), field.getValue().values().stream().map(JsonNode::asString).toList()));
        }
        return List.copyOf(fields);
    }

    /** The app category for a row of an official source, or empty if no rule matches. */
    public Optional<String> officialCategory(String sourceId, Function<String, String> row) {
        return first(sourceId, row);
    }

    /** The app category for an Overture place (fields {@code basic_category}, {@code taxonomy_primary}). */
    public Optional<String> overtureCategory(Function<String, String> row) {
        return first(OVERTURE, row);
    }

    /** The app category for an OSM feature (fields are tag keys such as {@code amenity}). */
    public Optional<String> osmCategory(Function<String, String> row) {
        return first(OSM, row);
    }

    /** Every OSM tag key with the values that map to some category, for osmium's tag filter. */
    public Map<String, Set<String>> osmTags() {
        var tags = new LinkedHashMap<String, Set<String>>();
        rules.stream().filter(rule -> rule.scope().equals(OSM)).flatMap(rule -> rule.fields().stream())
                .forEach(field -> tags.computeIfAbsent(field.field(), k -> new LinkedHashSet<>()).addAll(field.values()));
        return tags;
    }

    private Optional<String> first(String scope, Function<String, String> row) {
        return rules.stream()
                .filter(rule -> rule.scope().equals(scope))
                .filter(rule -> rule.matches(row))
                .map(Rule::category)
                .findFirst();
    }
}
