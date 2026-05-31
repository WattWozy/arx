package dev.archtelemetry.domain;

import java.util.Set;

public record ArchitectureProfile(
        Set<ModuleMetrics> moduleMetrics,
        Set<DependencyCycle> cycles,
        Set<Violation> violations,
        Set<ArchitectureCommunity> communities
) {
    public ArchitectureProfile {
        moduleMetrics = Set.copyOf(moduleMetrics);
        cycles = Set.copyOf(cycles);
        violations = Set.copyOf(violations);
        communities = Set.copyOf(communities);
    }

    public ArchitectureProfile(Set<ModuleMetrics> moduleMetrics, Set<DependencyCycle> cycles,
                                Set<Violation> violations) {
        this(moduleMetrics, cycles, violations, Set.of());
    }
}
