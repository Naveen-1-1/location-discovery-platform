package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryCatalogTest {

    @Test
    void readsTheTableOnceAndAnswersLookupsFromMemory() {
        var places = mock(PlaceRepository.class);
        when(places.categories()).thenReturn(List.of(new Category("food", "Food & drink"), new Category("gym", "Gyms")));
        var catalog = new CategoryCatalog(places);

        for (int i = 0; i < 100; i++) {
            assertThat(catalog.isKnown("food")).isTrue();
            assertThat(catalog.isKnown("casino")).isFalse();
        }
        assertThat(catalog.all()).extracting(Category::code).containsExactly("food", "gym");

        verify(places, times(1)).categories();
    }
}
