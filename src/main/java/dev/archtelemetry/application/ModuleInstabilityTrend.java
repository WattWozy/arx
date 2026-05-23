package dev.archtelemetry.application;

import java.util.List;

public record ModuleInstabilityTrend(String moduleName, List<Double> instabilityValues) {
    public ModuleInstabilityTrend {
        instabilityValues = List.copyOf(instabilityValues);
    }
}
