package dev.archtelemetry.domain;

import java.util.List;
import java.util.Set;

public record ArchitectureProfile(
        Set<ModuleMetrics> moduleMetrics,
        Set<DependencyCycle> cycles,
        Set<Violation> violations,
        List<RefactoringSuggestion> refactoringSuggestions,
        Set<ArchitectureCommunity> communities
) {
    public ArchitectureProfile {
        moduleMetrics = Set.copyOf(moduleMetrics);
        cycles = Set.copyOf(cycles);
        violations = Set.copyOf(violations);
        refactoringSuggestions = List.copyOf(refactoringSuggestions);
        communities = Set.copyOf(communities);
    }

    public ArchitectureProfile(Set<ModuleMetrics> moduleMetrics, Set<DependencyCycle> cycles,
                                Set<Violation> violations) {
        this(moduleMetrics, cycles, violations, List.of(), Set.of());
    }
}
