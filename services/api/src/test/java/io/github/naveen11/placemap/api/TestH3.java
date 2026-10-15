package io.github.naveen11.placemap.api;

import com.uber.h3core.H3Core;
import java.io.IOException;
import java.io.UncheckedIOException;

/** One H3 instance for every test: its native library only needs loading once. */
public final class TestH3 {

    public static final H3Core CORE;

    static {
        try {
            CORE = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("could not load the H3 native library", e);
        }
    }

    private TestH3() {
    }
}
