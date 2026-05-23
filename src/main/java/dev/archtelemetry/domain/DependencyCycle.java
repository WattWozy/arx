package dev.archtelemetry.domain;

import java.util.List;

public record DependencyCycle(List<Module> modules) {
    public DependencyCycle {
        modules = List.copyOf(modules);
    }
}
