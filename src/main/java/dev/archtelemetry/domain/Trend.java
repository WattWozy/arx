package dev.archtelemetry.domain;

import java.util.List;
import java.util.Set;

public final class Trend {

    public record SnapshotEntry(String commitId, Set<Violation> violations) {
        public SnapshotEntry {
            violations = Set.copyOf(violations);
        }

        public int violationCount() {
            return violations.size();
        }
    }

    private final List<SnapshotEntry> entries;

    public Trend(List<SnapshotEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<SnapshotEntry> entries() {
        return entries;
    }

    public DriftDirection direction() {
        if (entries.size() < 2) {
            return DriftDirection.STABLE;
        }
        int first = entries.get(0).violationCount();
        int last = entries.get(entries.size() - 1).violationCount();
        if (last < first) return DriftDirection.IMPROVING;
        if (last > first) return DriftDirection.DEGRADING;
        return DriftDirection.STABLE;
    }
}
