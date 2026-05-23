package dev.archtelemetry.domain;

import java.time.Instant;
import java.util.Set;

public record CommitEntry(String commitId, String authorEmail, Instant timestamp, Set<String> changedPaths) {
    public CommitEntry {
        changedPaths = Set.copyOf(changedPaths);
    }
}
