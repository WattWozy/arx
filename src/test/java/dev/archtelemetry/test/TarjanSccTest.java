package dev.archtelemetry.test;

import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarjanSccTest {

    private final ComputeMetrics computeMetrics = new ComputeMetrics(new AnalyzeSnapshot());

    @Test
    void diamondWithBackEdgeProducesExactlyOneScc() {
        Module a = new Module("A");
        Module b = new Module("B");
        Module c = new Module("C");
        Module d = new Module("D");
        Blueprint blueprint = new Blueprint(Set.of(a, b, c, d), Set.of());
        // Diamond: A->B, A->C, B->D, C->D, D->A (back-edge closes cycle)
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b),
                new Dependency(a, c),
                new Dependency(b, d),
                new Dependency(c, d),
                new Dependency(d, a)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertEquals(1, profile.cycles().size());
        DependencyCycle scc = profile.cycles().iterator().next();
        assertEquals(4, scc.modules().size());
    }

    @Test
    void twoIndependentCyclesProduceTwoSccs() {
        Module a = new Module("A");
        Module b = new Module("B");
        Module c = new Module("C");
        Module d = new Module("D");
        Blueprint blueprint = new Blueprint(Set.of(a, b, c, d), Set.of());
        // Two separate 2-cycles: A<->B and C<->D
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b),
                new Dependency(b, a),
                new Dependency(c, d),
                new Dependency(d, c)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertEquals(2, profile.cycles().size());
    }

    @Test
    void singleNodeLoopNotReportedAsCycle() {
        Module a = new Module("A");
        Module b = new Module("B");
        Blueprint blueprint = new Blueprint(Set.of(a, b), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertTrue(profile.cycles().isEmpty());
    }
}
