package dev.archtelemetry.application;

import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Violation;

import java.util.Set;
import java.util.stream.Collectors;

public final class AnalyzeSnapshot {

    public Set<Violation> analyze(Blueprint blueprint, Snapshot snapshot) {
        return snapshot.dependencies().stream()
                .filter(dep -> !blueprint.isAllowed(dep))
                .map(Violation::new)
                .collect(Collectors.toUnmodifiableSet());
    }
}
