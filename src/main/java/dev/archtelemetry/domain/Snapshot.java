package dev.archtelemetry.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record Snapshot(
        String commitId,
        Instant timestamp,
        Set<Dependency> dependencies,
        Map<Module, Integer> moduleWmc,
        Map<Module, Double> moduleAbstractness
) {
    public Snapshot {
        dependencies = Set.copyOf(dependencies);
        moduleWmc = Map.copyOf(moduleWmc);
        moduleAbstractness = Map.copyOf(moduleAbstractness);
    }

    public Snapshot(String commitId, Instant timestamp, Set<Dependency> dependencies) {
        this(commitId, timestamp, dependencies, Map.of(), Map.of());
    }

    public Snapshot(String commitId, Instant timestamp, Set<Dependency> dependencies,
                    Map<Module, Integer> moduleWmc) {
        this(commitId, timestamp, dependencies, moduleWmc, Map.of());
    }
}
