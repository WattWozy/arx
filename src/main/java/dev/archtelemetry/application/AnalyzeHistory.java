package dev.archtelemetry.application;

import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.Violation;

import java.util.List;
import java.util.Set;

public final class AnalyzeHistory {

    private final AnalyzeSnapshot analyzeSnapshot;

    public AnalyzeHistory(AnalyzeSnapshot analyzeSnapshot) {
        this.analyzeSnapshot = analyzeSnapshot;
    }

    public Trend analyze(Blueprint blueprint, List<Snapshot> snapshots) {
        List<Trend.SnapshotEntry> entries = snapshots.stream()
                .map(snapshot -> {
                    Set<Violation> violations = analyzeSnapshot.analyze(blueprint, snapshot);
                    return new Trend.SnapshotEntry(snapshot.commitId(), violations);
                })
                .toList();
        return new Trend(entries);
    }
}
