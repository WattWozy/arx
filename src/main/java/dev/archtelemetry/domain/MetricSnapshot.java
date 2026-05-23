package dev.archtelemetry.domain;

import java.time.Instant;

public record MetricSnapshot(
        String commitHash,
        Instant timestamp,
        String moduleName,
        int fanIn,
        int fanOut,
        double instability,
        double abstractness,
        double distanceFromMainSequence,
        double hubScore,
        double crapScore,
        int wmc,
        double pageRank,
        double betweenness,
        double testDebtScore,
        double churnAcceleration,
        double busFactorRisk
) {}
