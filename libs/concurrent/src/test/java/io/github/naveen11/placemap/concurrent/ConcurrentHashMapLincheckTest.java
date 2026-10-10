package io.github.naveen11.placemap.concurrent;

import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 toolchain gate (positive control): Lincheck must accept a structure that is known to be linearizable.
 */
// With javac -parameters, Lincheck resolves every operation parameter by name, so each one needs a generator.
@Param(name = "key", gen = IntGen.class, conf = "1:3")
@Param(name = "value", gen = IntGen.class, conf = "1:9")
public class ConcurrentHashMapLincheckTest {

    private final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();

    @Operation
    public Integer put(@Param(name = "key") int key, @Param(name = "value") int value) {
        return map.put(key, value);
    }

    @Operation
    public Integer get(@Param(name = "key") int key) {
        return map.get(key);
    }

    @Operation
    public Integer remove(@Param(name = "key") int key) {
        return map.remove(key);
    }

    @Test
    void isLinearizable() {
        // Lincheck's defaults (100 iterations x 10,000 invocations) took ~170 s on an M4: model checking walks
        // ConcurrentHashMap's own internals too. A positive control only needs a smaller, still non-trivial run.
        new ModelCheckingOptions()
                .iterations(20)
                .invocationsPerIteration(1_000)
                .check(ConcurrentHashMapLincheckTest.class);
    }
}
