package io.github.naveen11.placemap.ingest.catalog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Maps a source's own classification onto the app categories, as written in {@code data/categories.yaml}
 * (ADR 0032). Rules are data, not code: an official source matches a category when every listed field has one of
 * the listed values; a field named {@code X_prefix} matches values of {@code X} that start with a listed value; an
 * empty rule ({@code {}}) matches every row. Categories are tried in file order, and the first match wins.
 * Immutable, so parse workers share one instance.
 */
public final class CategoryRules {

    private record FieldRule(String field, boolean prefix, List<String> values) {

        boolean matches(Function<String, String> row) {
            String value = row.apply(field);
            if (value == null) {
                return false;
            }
            return prefix ? values.stream().anyMatch(value::startsWith) : values.contains(value);
        }
    }

    private record Rule(String category, String source, List<FieldRule> fields) {
    }

    private final List<Rule> officialRules;

    private CategoryRules(List<Rule> officialRules) {
        this.officialRules = officialRules;
    }

    public static CategoryRules load(Path file) {
        JsonNode categories = new YAMLMapper().readTree(file).required("categories");
        var rules = new ArrayList<Rule>();
        for (Map.Entry<String, JsonNode> category : categories.properties()) {
            JsonNode official = category.getValue().path("official");
            for (Map.Entry<String, JsonNode> source : official.properties()) {
                var fields = new ArrayList<FieldRule>();
                for (Map.Entry<String, JsonNode> field : source.getValue().properties()) {
                    boolean prefix = field.getKey().endsWith("_prefix");
                    String name = prefix ? field.getKey().substring(0, field.getKey().length() - "_prefix".length())
                            : field.getKey();
                    // YAML may write codes as numbers (location_type: [0, 1]); rows hold text.
                    List<String> values = field.getValue().values().stream().map(JsonNode::asString).toList();
                    fields.add(new FieldRule(name, prefix, values));
                }
                rules.add(new Rule(category.getKey(), source.getKey(), List.copyOf(fields)));
            }
        }
        return new CategoryRules(List.copyOf(rules));
    }

    /** The app category for a row of an official source, or empty if no rule matches. */
    public Optional<String> officialCategory(String sourceId, Function<String, String> row) {
        return officialRules.stream()
                .filter(rule -> rule.source().equals(sourceId))
                .filter(rule -> rule.fields().stream().allMatch(f -> f.matches(row)))
                .map(Rule::category)
                .findFirst();
    }

    /** Whether the file has any rule for this source; an importer without rules would leave every row uncategorised. */
    public boolean hasOfficialRules(String sourceId) {
        return officialRules.stream().anyMatch(rule -> rule.source().equals(sourceId));
    }
}
