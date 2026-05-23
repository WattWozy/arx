package dev.archtelemetry.domain;

import java.util.Optional;
import java.util.Set;

public final class Blueprint {

    private final Set<Module> modules;
    private final Set<Dependency> allowedDependencies;
    private final Optional<String> scope;

    public Blueprint(Set<Module> modules, Set<Dependency> allowedDependencies) {
        this(modules, allowedDependencies, Optional.empty());
    }

    public Blueprint(Set<Module> modules, Set<Dependency> allowedDependencies, Optional<String> scope) {
        this.modules = Set.copyOf(modules);
        this.allowedDependencies = Set.copyOf(allowedDependencies);
        this.scope = scope;
    }

    public boolean isAllowed(Dependency dependency) {
        return allowedDependencies.contains(dependency);
    }

    public Set<Module> modules() {
        return modules;
    }

    public Set<Dependency> allowedDependencies() {
        return allowedDependencies;
    }

    /** Relative path from git root to scan root, empty if scan root equals git root. */
    public Optional<String> scope() {
        return scope;
    }
}
