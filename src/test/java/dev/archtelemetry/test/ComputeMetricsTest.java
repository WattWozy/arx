package dev.archtelemetry.test;

import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeMetricsTest {

    private final ComputeMetrics computeMetrics = new ComputeMetrics(new AnalyzeSnapshot());

    @Test
    void cycleDetectionFindsSimpleTwoNodeCycle() {
        Module a = new Module("A");
        Module b = new Module("B");
        Blueprint blueprint = new Blueprint(Set.of(a, b), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b),
                new Dependency(b, a)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertEquals(1, profile.cycles().size());
    }

    @Test
    void cycleDetectionFindsLongerThreeNodeCycle() {
        Module a = new Module("A");
        Module b = new Module("B");
        Module c = new Module("C");
        Blueprint blueprint = new Blueprint(Set.of(a, b, c), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b),
                new Dependency(b, c),
                new Dependency(c, a)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertEquals(1, profile.cycles().size());
    }

    @Test
    void cycleDetectionReturnsEmptyForAcyclicGraph() {
        Module a = new Module("A");
        Module b = new Module("B");
        Module c = new Module("C");
        Blueprint blueprint = new Blueprint(Set.of(a, b, c), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b),
                new Dependency(b, c)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertTrue(profile.cycles().isEmpty());
    }

    @Test
    void fanInAndFanOutComputedFromSnapshot() {
        Module domain = new Module("domain");
        Module app = new Module("application");
        // app -> domain: fanOut(app)=1, fanIn(domain)=1
        Blueprint blueprint = new Blueprint(Set.of(domain, app), Set.of(new Dependency(app, domain)));
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(new Dependency(app, domain)));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        var appMetrics = profile.moduleMetrics().stream()
                .filter(m -> m.module().equals(app)).findFirst().orElseThrow();
        var domainMetrics = profile.moduleMetrics().stream()
                .filter(m -> m.module().equals(domain)).findFirst().orElseThrow();

        assertEquals(0, appMetrics.fanIn());
        assertEquals(1, appMetrics.fanOut());
        assertEquals(1, domainMetrics.fanIn());
        assertEquals(0, domainMetrics.fanOut());
    }
}
