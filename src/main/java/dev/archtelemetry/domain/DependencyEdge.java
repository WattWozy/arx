package dev.archtelemetry.domain;

public record DependencyEdge(
        Module source,
        Module target,
        EdgeKind kind
) {}
