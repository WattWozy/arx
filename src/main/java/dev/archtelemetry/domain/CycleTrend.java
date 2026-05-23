package dev.archtelemetry.domain;

import java.time.Instant;

public record CycleTrend(String commitHash, Instant timestamp, int cycleCount) {}
