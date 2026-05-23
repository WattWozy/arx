package dev.archtelemetry.domain;

import java.time.Instant;

public record HotspotSnapshot(
        String commitHash,
        Instant timestamp,
        String filePath,
        int churn,
        int complexity,
        double score
) {}
