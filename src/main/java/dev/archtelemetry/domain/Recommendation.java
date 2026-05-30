package dev.archtelemetry.domain;

import java.util.List;

public record Recommendation(
        Module target,
        Severity severity,
        String code,
        String summary,
        String rationale,
        List<DependencyEdge> evidence
) {
    public Recommendation {
        evidence = List.copyOf(evidence);
    }
}
