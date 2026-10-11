package io.github.naveen11.placemap.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** The search box: names and addresses together, addresses shared, and Photon never holding up the answer. */
class SearchServiceTest {

    private static final SearchProperties PROPERTIES = new SearchProperties(URI.create("https://photon.test/api/"),
            Duration.ofMillis(300), 1, 100, Duration.ofMinutes(10), Duration.ofMillis(1500), 8, 5, "-71.2,42.2,-70.8,42.4");
    private static final PlaceRepository.NameMatch TATTE =
            new PlaceRepository.NameMatch(7, "Tatte Bakery & Café", "food", "1 Main St", 42.356, -71.066, 120);
    private static final AddressMatch ELM = new AddressMatch("12 Elm Street, Somerville", 42.39, -71.12, "house");

    private final PlaceRepository places = mock(PlaceRepository.class);
    private final PhotonClient photon = mock(PhotonClient.class);

    private SearchService service() {
        return new SearchService(places, photon, Bulkhead.ofDefaults("database"),
                Bulkhead.of("photon", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()),
                PROPERTIES, new SimpleMeterRegistry());
    }

    private static Deadline soon() {
        return Deadline.after(Duration.ofMillis(1500));
    }

    @Test
    void namesAndAddressesComeTogetherAndAnAddressAnswerIsShared() {
        when(places.searchNames(eq("tatte"), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(TATTE));
        when(photon.search(anyString(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(ELM));
        var service = service();

        // Both near 42.35, -71.07 once rounded to about a kilometre, as the cache key and Photon see them.
        SearchService.SearchResult first = service.search("  tatte ", 42.3512, -71.0681, soon());
        SearchService.SearchResult second = service.search("Tatte", 42.3519, -71.0688, soon());

        assertThat(first.places()).containsExactly(TATTE);
        assertThat(first.addresses()).containsExactly(ELM);
        assertThat(first.addressStatus()).isEqualTo("ok");
        assertThat(second.addresses()).containsExactly(ELM);
        verify(photon, times(1)).search(anyString(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void twoLettersSearchNamesOnly() {
        var service = service();

        assertThat(service.search("ta", 42.355, -71.0656, soon()).addressStatus()).isEqualTo("too_short");
        verify(places).searchNames(eq("ta"), anyDouble(), anyDouble(), anyInt());
        verify(photon, never()).search(anyString(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void aSlowPhotonIsCutOffAndThePlacesComeAlone() {
        when(places.searchNames(anyString(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(TATTE));
        when(photon.search(anyString(), anyDouble(), anyDouble(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return List.of(ELM);
        });

        long start = System.nanoTime();
        SearchService.SearchResult result = service().search("tatte", 42.355, -71.0656, soon());

        assertThat(result.addressStatus()).isEqualTo("timeout");
        assertThat(result.places()).containsExactly(TATTE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(800));
    }

    @Test
    void whenPhotonHasAllTheCallsItCanTakeTheSearchSaysBusy() throws Exception {
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(photon.search(eq("first"), anyDouble(), anyDouble(), anyInt())).thenAnswer(invocation -> {
            inside.countDown();
            release.await();
            return List.of();
        });
        var service = service();
        Thread.ofVirtual().start(() -> service.search("first", 42.355, -71.0656, Deadline.after(Duration.ofSeconds(5))));
        inside.await();

        assertThat(service.search("second", 42.355, -71.0656, soon()).addressStatus()).isEqualTo("busy");
        release.countDown();
    }

    @Test
    void aFailedAddressSearchIsNotRemembered() {
        when(photon.search(anyString(), anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new IllegalStateException("HTTP 503"))
                .thenReturn(List.of(ELM));
        var service = service();

        assertThat(service.search("elm st", 42.39, -71.12, soon()).addressStatus()).isEqualTo("unavailable");
        assertThat(service.search("elm st", 42.39, -71.12, soon()).addresses()).containsExactly(ELM);
    }
}
