package dev.archtelemetry;

import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleMetricsTest {

    @Test
    void fanInAndFanOutStoredCorrectly() {
        ModuleMetrics m = ModuleMetrics.compute(new Module("x"), 2, 3);
        assertEquals(2, m.fanIn());
        assertEquals(3, m.fanOut());
    }

    @Test
    void instabilityIsCorrect() {
        // fanIn=1, fanOut=3 → 3/4 = 0.75
        ModuleMetrics m = ModuleMetrics.compute(new Module("x"), 1, 3);
        assertEquals(0.75, m.instability(), 1e-9);
    }

    @Test
    void instabilityIsZeroWhenBothZero() {
        ModuleMetrics m = ModuleMetrics.compute(new Module("x"), 0, 0);
        assertEquals(0.0, m.instability(), 1e-9);
    }

    @Test
    void purelyStableModuleHasInstabilityZero() {
        // fanIn=5, fanOut=0 → 0/(5+0) = 0.0
        ModuleMetrics m = ModuleMetrics.compute(new Module("domain"), 5, 0);
        assertEquals(0.0, m.instability(), 1e-9);
    }

    @Test
    void purelyUnstableModuleHasInstabilityOne() {
        // fanIn=0, fanOut=4 → 4/(0+4) = 1.0
        ModuleMetrics m = ModuleMetrics.compute(new Module("infra"), 0, 4);
        assertEquals(1.0, m.instability(), 1e-9);
    }
}
