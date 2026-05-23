package dev.archtelemetry.domain;

public record ViolationRecord(Violation violation, int ageInSnapshots, boolean isChronic) {}
