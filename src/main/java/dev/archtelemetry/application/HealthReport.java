package dev.archtelemetry.application;

import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.DriftDirection;
import dev.archtelemetry.domain.Violation;
import dev.archtelemetry.domain.ViolationRecord;

import java.util.List;
import java.util.Set;

public record HealthReport(
        int totalViolations,
        Set<Violation> newViolations,
        Set<Violation> resolvedViolations,
        DriftDirection driftDirection,
        int snapshotsAnalyzed,
        ArchitectureProfile latestProfile,
        List<InstabilityWarning> instabilityWarnings,
        List<ModuleInstabilityTrend> instabilityTrends,
        List<ViolationRecord> violationRecords
) {}
