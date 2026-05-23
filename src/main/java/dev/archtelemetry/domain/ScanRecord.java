package dev.archtelemetry.domain;

import java.time.Instant;
import java.util.List;

public record ScanRecord(
        String repoPath,
        String commitHash,
        Instant commitTime,
        String blueprintHash,
        String blueprintText,
        List<Violation> violations,
        List<ModuleMetrics> moduleMetrics,
        List<Hotspot> hotspots,
        List<DependencyCycle> cycles
) {
    public ScanRecord {
        violations = List.copyOf(violations);
        moduleMetrics = List.copyOf(moduleMetrics);
        hotspots = List.copyOf(hotspots);
        cycles = List.copyOf(cycles);
    }
}
