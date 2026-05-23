package dev.archtelemetry.domain;

public record MethodCoverage(
        String fqn,
        int cyclomaticComplexity,
        double lineCoverage
) {
    public double crap() {
        double cc = cyclomaticComplexity;
        return cc * cc * Math.pow(1.0 - lineCoverage, 3) + cc;
    }
}
