package dev.archtelemetry.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class DependencyGraph {

    private final List<DependencyEdge> edges;
    private final Set<Module> modules;

    private DependencyGraph(List<DependencyEdge> edges, Set<Module> modules) {
        this.edges = List.copyOf(edges);
        this.modules = Set.copyOf(modules);
    }

    /**
     * Builds a graph from observed snapshot dependencies.
     * Targets not present in the blueprint module set are treated as EXTERNAL.
     */
    public static DependencyGraph from(Set<Dependency> observed, Set<Module> modules) {
        List<DependencyEdge> edges = observed.stream()
                .map(dep -> {
                    LayerKind srcKind = modules.contains(dep.source())
                            ? LayerKind.resolve(dep.source())
                            : LayerKind.EXTERNAL;
                    LayerKind tgtKind = modules.contains(dep.target())
                            ? LayerKind.resolve(dep.target())
                            : LayerKind.EXTERNAL;
                    EdgeKind kind = EdgeKind.classify(srcKind, tgtKind);
                    return new DependencyEdge(dep.source(), dep.target(), kind);
                })
                .toList();
        return new DependencyGraph(edges, modules);
    }

    public List<DependencyEdge> edges() {
        return edges;
    }

    public List<DependencyEdge> edgesFrom(Module source) {
        return edges.stream().filter(e -> e.source().equals(source)).toList();
    }

    public List<DependencyEdge> edgesTo(Module target) {
        return edges.stream().filter(e -> e.target().equals(target)).toList();
    }

    public List<DependencyEdge> edgesByKind(EdgeKind kind) {
        return edges.stream().filter(e -> e.kind() == kind).toList();
    }

    public Set<Module> lateralCouplings(Module source) {
        return edgesFrom(source).stream()
                .filter(e -> e.kind() == EdgeKind.LATERAL_COUPLING)
                .map(DependencyEdge::target)
                .collect(Collectors.toSet());
    }

    /**
     * Returns true if the adapter module accesses the domain layer directly
     * and at least one PORT module exists in the blueprint — indicating a port bypass.
     */
    public boolean hasBypassedPort(Module adapter) {
        boolean hasDomainAccess = edgesFrom(adapter).stream()
                .anyMatch(e -> e.kind() == EdgeKind.DOMAIN_ACCESS);
        if (!hasDomainAccess) return false;
        return modules.stream().anyMatch(m -> LayerKind.resolve(m) == LayerKind.PORT);
    }
}
