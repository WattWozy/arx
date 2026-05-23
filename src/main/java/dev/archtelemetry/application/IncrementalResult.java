package dev.archtelemetry.application;

import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Violation;

import java.util.Set;

public record IncrementalResult(
        Snapshot updatedSnapshot,
        Set<Violation> allViolations,
        Set<Violation> newViolations
) {}
