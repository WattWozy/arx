package dev.archtelemetry.domain;

import java.util.Set;

public record ArchitectureCommunity(Set<Module> modules, String suggestedName) {
    public ArchitectureCommunity {
        modules = Set.copyOf(modules);
    }
}
