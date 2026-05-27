package dev.archtelemetry.test;

import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.domain.ArchitectureCommunity;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommunityDetectionTest {

    private final ComputeMetrics computeMetrics = new ComputeMetrics(new AnalyzeSnapshot());

    @Test
    void twoCoupledModulesAndOneIsolatedFormTwoCommunities() {
        Module a = new Module("A");
        Module b = new Module("B");
        Module c = new Module("C");
        Blueprint blueprint = new Blueprint(Set.of(a, b, c), Set.of());
        // A <-> B are connected (undirected); C is isolated
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(new Dependency(a, b)));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertEquals(2, profile.communities().size(),
                "A-B cluster and isolated C should produce 2 communities");
    }

    @Test
    void allConnectedModulesFormOneCommunity() {
        Module a = new Module("A");
        Module b = new Module("B");
        Module c = new Module("C");
        Blueprint blueprint = new Blueprint(Set.of(a, b, c), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(
                new Dependency(a, b),
                new Dependency(b, c)
        ));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertEquals(1, profile.communities().size(), "Transitively connected A-B-C = one community");
    }

    @Test
    void isolatedModulesEachFormOwnCommunity() {
        Module a = new Module("A");
        Module b = new Module("B");
        Blueprint blueprint = new Blueprint(Set.of(a, b), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of());

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertEquals(2, profile.communities().size(), "No deps: each module is its own community");
    }

    @Test
    void crossLayerCommunityDetected() {
        Module domain = new Module("domain", java.util.List.of("dev.domain.**"), 0);
        Module infra = new Module("infra", java.util.List.of("dev.infra.**"), 2);
        Blueprint blueprint = new Blueprint(Set.of(domain, infra), Set.of());
        // Direct cross-layer coupling (domain -> infra, which would be a violation)
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(new Dependency(domain, infra)));

        ArchitectureProfile profile = computeMetrics.compute(blueprint, snapshot);

        assertEquals(1, profile.communities().size());
        ArchitectureCommunity community = profile.communities().iterator().next();
        assertEquals(Set.of(domain, infra), community.modules());
    }
}
