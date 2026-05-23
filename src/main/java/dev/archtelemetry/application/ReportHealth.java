package dev.archtelemetry.application;

import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.DriftDirection;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.Violation;
import dev.archtelemetry.domain.ViolationRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReportHealth {

    private static final double INSTABILITY_THRESHOLD = 0.5;
    private static final int INNER_LAYER_MAX = 1;
    private static final int CHRONIC_THRESHOLD = 3;

    public HealthReport report(Trend trend) {
        return report(trend, List.of());
    }

    public HealthReport report(Trend trend, List<ArchitectureProfile> profiles) {
        List<Trend.SnapshotEntry> entries = trend.entries();

        if (entries.isEmpty()) {
            return new HealthReport(0, Set.of(), Set.of(), DriftDirection.STABLE,
                    0, null, List.of(), List.of(), List.of());
        }

        Trend.SnapshotEntry latest = entries.get(entries.size() - 1);
        Set<Violation> latestViolations = latest.violations();

        Set<Violation> newViolations;
        Set<Violation> resolvedViolations;

        if (entries.size() < 2) {
            newViolations = Set.copyOf(latestViolations);
            resolvedViolations = Set.of();
        } else {
            Trend.SnapshotEntry previous = entries.get(entries.size() - 2);
            Set<Violation> previousViolations = previous.violations();

            Set<Violation> nv = new HashSet<>(latestViolations);
            nv.removeAll(previousViolations);
            newViolations = Set.copyOf(nv);

            Set<Violation> rv = new HashSet<>(previousViolations);
            rv.removeAll(latestViolations);
            resolvedViolations = Set.copyOf(rv);
        }

        ArchitectureProfile latestProfile = profiles.isEmpty() ? null : profiles.get(profiles.size() - 1);
        List<InstabilityWarning> warnings = computeWarnings(latestProfile);
        List<ModuleInstabilityTrend> instabilityTrends = computeInstabilityTrends(profiles);
        List<ViolationRecord> violationRecords = computeViolationRecords(entries);

        return new HealthReport(
                latestViolations.size(),
                newViolations,
                resolvedViolations,
                trend.direction(),
                entries.size(),
                latestProfile,
                warnings,
                instabilityTrends,
                violationRecords
        );
    }

    private List<ViolationRecord> computeViolationRecords(List<Trend.SnapshotEntry> entries) {
        if (entries.isEmpty()) return List.of();
        Set<Violation> latest = entries.get(entries.size() - 1).violations();
        List<ViolationRecord> records = new ArrayList<>();
        for (Violation v : latest) {
            int age = 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).violations().contains(v)) {
                    age++;
                } else {
                    break;
                }
            }
            records.add(new ViolationRecord(v, age, age >= CHRONIC_THRESHOLD));
        }
        return Collections.unmodifiableList(records);
    }

    private List<InstabilityWarning> computeWarnings(ArchitectureProfile profile) {
        if (profile == null) return List.of();
        List<InstabilityWarning> warnings = new ArrayList<>();
        for (ModuleMetrics m : profile.moduleMetrics()) {
            int layer = m.module().layer();
            if (layer >= 0 && layer <= INNER_LAYER_MAX && m.instability() > INSTABILITY_THRESHOLD) {
                warnings.add(new InstabilityWarning(m.module(),
                        "inner layer (layer=" + layer + ") has high instability " +
                        String.format("%.2f", m.instability())));
            }
        }
        return Collections.unmodifiableList(warnings);
    }

    private List<ModuleInstabilityTrend> computeInstabilityTrends(List<ArchitectureProfile> profiles) {
        if (profiles.isEmpty()) return List.of();
        Map<String, List<Double>> values = new LinkedHashMap<>();
        for (ArchitectureProfile profile : profiles) {
            for (ModuleMetrics m : profile.moduleMetrics()) {
                values.computeIfAbsent(m.module().name(), k -> new ArrayList<>())
                      .add(m.instability());
            }
        }
        return values.entrySet().stream()
                .map(e -> new ModuleInstabilityTrend(e.getKey(), e.getValue()))
                .toList();
    }
}
