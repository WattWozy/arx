package dev.archtelemetry.application;

import dev.archtelemetry.domain.Module;

public record InstabilityWarning(Module module, String reason) {}
