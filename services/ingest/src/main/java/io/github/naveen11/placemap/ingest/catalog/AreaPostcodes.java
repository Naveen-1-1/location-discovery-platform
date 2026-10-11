package io.github.naveen11.placemap.ingest.catalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The four towns' street ZIP codes, from {@code postcodes} in {@code data/area.yaml}; sorted, without repeats. */
public record AreaPostcodes(List<String> codes) {

    public AreaPostcodes {
        codes = List.copyOf(new TreeSet<>(codes));
    }

    public static AreaPostcodes load(Path areaYaml) {
        JsonNode towns = new YAMLMapper().readTree(areaYaml).required("postcodes");
        var codes = new TreeSet<String>();
        for (Map.Entry<String, JsonNode> town : towns.properties()) {
            town.getValue().values().forEach(code -> codes.add(code.asString()));
        }
        return new AreaPostcodes(List.copyOf(codes));
    }

    public boolean contains(String zip) {
        return zip != null && codes.contains(zip);
    }
}
