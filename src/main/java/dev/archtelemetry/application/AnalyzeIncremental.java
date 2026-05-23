package dev.archtelemetry.application;

import dev.archtelemetry.application.port.DependencyResolver;
import dev.archtelemetry.application.port.ResolvedData;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Violation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class AnalyzeIncremental {

    private final DependencyResolver resolver;
    private final AnalyzeSnapshot analyzeSnapshot;

    public AnalyzeIncremental(DependencyResolver resolver, AnalyzeSnapshot analyzeSnapshot) {
        this.resolver = resolver;
        this.analyzeSnapshot = analyzeSnapshot;
    }

    /**
     * Re-resolves changedFiles against the working tree, merges into previousSnapshot's
     * dependency graph, and returns violations introduced by the change.
     */
    public IncrementalResult analyze(Set<Path> changedFiles, Blueprint blueprint, Snapshot previousSnapshot) {
        ResolvedData newData = resolver.resolve(changedFiles);

        // Modules touched by the changed files (may have zero deps if files are new/empty)
        Set<Module> touchedModules = new HashSet<>();
        newData.dependencies().forEach(d -> touchedModules.add(d.source()));
        newData.moduleWmc().keySet().forEach(touchedModules::add);

        // Keep previous deps only from modules NOT touched by the change
        Set<Dependency> retained = previousSnapshot.dependencies().stream()
                .filter(d -> !touchedModules.contains(d.source()))
                .collect(Collectors.toUnmodifiableSet());

        Set<Dependency> merged = new HashSet<>(retained);
        merged.addAll(newData.dependencies());

        Map<Module, Integer> mergedWmc = new HashMap<>(previousSnapshot.moduleWmc());
        touchedModules.forEach(mergedWmc::remove);
        newData.moduleWmc().forEach((m, wmc) -> mergedWmc.merge(m, wmc, Integer::sum));

        Map<Module, Double> mergedAbstractness = new HashMap<>(previousSnapshot.moduleAbstractness());
        touchedModules.forEach(mergedAbstractness::remove);
        mergedAbstractness.putAll(newData.moduleAbstractness());

        Snapshot updatedSnapshot = new Snapshot("incremental", Instant.now(), merged,
                Map.copyOf(mergedWmc), Map.copyOf(mergedAbstractness));

        Set<Violation> previousViolations = analyzeSnapshot.analyze(blueprint, previousSnapshot);
        Set<Violation> allViolations = analyzeSnapshot.analyze(blueprint, updatedSnapshot);
        Set<Violation> newViolations = allViolations.stream()
                .filter(v -> !previousViolations.contains(v))
                .collect(Collectors.toUnmodifiableSet());

        return new IncrementalResult(updatedSnapshot, allViolations, newViolations);
    }
}
