package dev.archtelemetry.domain;

public record Hotspot(String filePath, int churn, int complexity, double score) {}
