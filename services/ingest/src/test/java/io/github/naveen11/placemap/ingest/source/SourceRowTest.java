package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The row hash decides whether a re-import rewrites a record, so it must be stable and see every stored column. */
class SourceRowTest {

    private static SourceRow row(String name, Double lon, Map<String, String> attrs) {
        return SourceRow.of("k", name, "food", lon, lon == null ? null : 42.0, "1 Main", "02108", null, null, attrs);
    }

    @Test
    void sameContentGivesTheSameHashWhateverTheAttributeOrder() {
        var forward = new LinkedHashMap<String, String>();
        forward.put("a", "1");
        forward.put("b", "2");
        var backward = new LinkedHashMap<String, String>();
        backward.put("b", "2");
        backward.put("a", "1");

        assertThat(row("Cafe", -71.0, forward).rowHash()).isEqualTo(row("Cafe", -71.0, backward).rowHash());
    }

    @Test
    void anyStoredChangeChangesTheHash() {
        String base = row("Cafe", -71.0, Map.of("a", "1")).rowHash();

        assertThat(row("Café", -71.0, Map.of("a", "1")).rowHash()).isNotEqualTo(base);
        assertThat(row("Cafe", -71.00001, Map.of("a", "1")).rowHash()).isNotEqualTo(base);
        assertThat(row("Cafe", null, Map.of("a", "1")).rowHash()).isNotEqualTo(base);
        assertThat(row("Cafe", -71.0, Map.of("a", "2")).rowHash()).isNotEqualTo(base);
        assertThat(row("Cafe", -71.0, Map.of()).rowHash()).isNotEqualTo(base);
    }

    @Test
    void nullAttributesAreLeftOutAndTheKeyIsNotHashed() {
        var withNull = new LinkedHashMap<String, String>();
        withNull.put("a", "1");
        withNull.put("b", null);
        SourceRow a = row("Cafe", -71.0, withNull);
        SourceRow b = SourceRow.of("other-key", "Cafe", "food", -71.0, 42.0, "1 Main", "02108", null, null, Map.of("a", "1"));

        assertThat(a.attrs()).containsOnlyKeys("a");
        assertThat(a.rowHash()).isEqualTo(b.rowHash());
    }

    @Test
    void locationIsAllOrNothing() {
        assertThatThrownBy(() -> SourceRow.of("k", "Cafe", null, -71.0, null, null, null, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(row("Cafe", null, Map.of()).ewkt()).isNull();
    }
}
