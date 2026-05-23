package dev.archtelemetry.domain;

import java.time.Instant;

public record ViolationTrend(String commitHash, Instant timestamp, int violationCount) {}
