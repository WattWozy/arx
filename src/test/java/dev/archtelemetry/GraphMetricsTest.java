package dev.archtelemetry;

import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphMetricsTest {

    private final ComputeMetrics computeMetrics = new ComputeMetrics(new AnalyzeSnapshot());

    @Test
    void starTopology_centerHasHighestBetweenness() {
        // center -> leaf1, leaf2, leaf3, leaf4
        // Also leaf1..4 -> center so center has real in+out edges
        Module center = new Module("center");
        Module leaf1 = new Module("leaf1");
        Module leaf2 = new Module("leaf2");
        Module leaf3 = new Module("leaf3");
        Module leaf4 = new Module("leaf4");

        Blueprint blueprint = new Blueprint(Set.of(center, leaf1, leaf2, leaf3, leaf4), Set.of());
        // All leaves point to center (center is a dependency hub)
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(leaf1, center),
                new Dependency(leaf2, center),
                new Dependency(leaf3, center),
                new Dependency(leaf4, center)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        ModuleMetrics centerMetrics = profile.moduleMetrics().stream()
                .filter(m -> m.module().equals(center)).findFirst().orElseThrow();

        double maxBetweenness = profile.moduleMetrics().stream()
                .mapToDouble(ModuleMetrics::betweenness)
                .max().orElse(0.0);

        assertEquals(centerMetrics.betweenness(), maxBetweenness, 1e-9,
                "Center module should have the highest betweenness in a star topology");
    }

    @Test
    void linearChain_middleHasNonzeroBetweenness() {
        // a -> b -> c -> d: b and c are on the path from a to d
        Module a = new Module("a");
        Module b = new Module("b");
        Module c = new Module("c");
        Module d = new Module("d");

        Blueprint blueprint = new Blueprint(Set.of(a, b, c, d), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b),
                new Dependency(b, c),
                new Dependency(c, d)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        ModuleMetrics bMetrics = profile.moduleMetrics().stream()
                .filter(m -> m.module().equals(b)).findFirst().orElseThrow();
        ModuleMetrics cMetrics = profile.moduleMetrics().stream()
                .filter(m -> m.module().equals(c)).findFirst().orElseThrow();

        assertTrue(bMetrics.betweenness() > 0.0, "b should have nonzero betweenness in a chain");
        assertTrue(cMetrics.betweenness() > 0.0, "c should have nonzero betweenness in a chain");
    }

    @Test
    void pageRank_sumsToApproximatelyOne() {
        Module a = new Module("a");
        Module b = new Module("b");
        Module c = new Module("c");
        Blueprint blueprint = new Blueprint(Set.of(a, b, c), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b),
                new Dependency(b, c)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        double sum = profile.moduleMetrics().stream().mapToDouble(ModuleMetrics::pageRank).sum();
        assertEquals(1.0, sum, 0.05, "PageRank values should sum to approximately 1.0");
    }

    @Test
    void hubScore_dependsOnPageRankAndBetweennessAndWmc() {
        Module hub = new Module("hub");
        Module spoke = new Module("spoke");
        Blueprint blueprint = new Blueprint(Set.of(hub, spoke), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(spoke, hub)
        ), Map.of(hub, 10)); // hub has WMC=10

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        ModuleMetrics hubMetrics = profile.moduleMetrics().stream()
                .filter(m -> m.module().equals(hub)).findFirst().orElseThrow();
        // hubScore = pageRank * betweenness * max(1, wmc)
        double expected = hubMetrics.pageRank() * hubMetrics.betweenness() * Math.max(1, hubMetrics.wmc());
        assertEquals(expected, hubMetrics.hubScore(), 1e-9);
    }
}
