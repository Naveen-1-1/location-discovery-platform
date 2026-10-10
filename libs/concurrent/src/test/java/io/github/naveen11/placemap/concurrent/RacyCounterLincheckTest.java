package io.github.naveen11.placemap.concurrent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jetbrains.lincheck.LincheckAssertionError;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 toolchain gate (negative control): a plain {@code ++value} loses updates when two threads interleave,
 * so Lincheck must find a non-linearizable execution. If this test ever passes silently, the tool is not
 * actually exploring interleavings and every "green" Lincheck result would be meaningless.
 */
public class RacyCounterLincheckTest {

    private int value;

    @Operation
    public int incrementAndGet() {
        return ++value;
    }

    @Test
    void lincheckFindsTheLostUpdate() {
        assertThatThrownBy(() -> new ModelCheckingOptions().check(RacyCounterLincheckTest.class))
                .isInstanceOf(LincheckAssertionError.class);
    }
}
