package dev.archtelemetry;

import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DependencyEdge;
import dev.archtelemetry.domain.DependencyGraph;
import dev.archtelemetry.domain.EdgeKind;
import dev.archtelemetry.domain.LayerKind;
import dev.archtelemetry.domain.Module;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphTest {

    private static final Module domain     = new Module("domain",      List.of("com.example.domain.**"),     0);
    private static final Module port       = new Module("application-port", List.of("com.example.port.**"), 1);
    private static final Module app        = new Module("application",  List.of("com.example.app.**"),       2);
    private static final Module adapterA   = new Module("adapter-http", List.of("com.example.http.**"),      3);
    private static final Module adapterB   = new Module("adapter-db",   List.of("com.example.db.**"),        3);
    private static final Module external   = new Module("ext-lib",      List.of("org.hibernate.**"),         -1);

    private static final Set<Module> modules = Set.of(domain, port, app, adapterA, adapterB);

    @Test
    void adapterToAdapterIsLateralCoupling() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, adapterB)), modules);
        assertEquals(EdgeKind.LATERAL_COUPLING, g.edgesFrom(adapterA).get(0).kind());
    }

    @Test
    void adapterToPortIsPortAccess() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, port)), modules);
        assertEquals(EdgeKind.PORT_ACCESS, g.edgesFrom(adapterA).get(0).kind());
    }

    @Test
    void adapterToDomainIsDomainAccess() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, domain)), modules);
        assertEquals(EdgeKind.DOMAIN_ACCESS, g.edgesFrom(adapterA).get(0).kind());
    }

    @Test
    void adapterToApplicationIsInwardFlow() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, app)), modules);
        assertEquals(EdgeKind.INWARD_FLOW, g.edgesFrom(adapterA).get(0).kind());
    }

    @Test
    void applicationToDomainIsInwardFlow() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(app, domain)), modules);
        assertEquals(EdgeKind.INWARD_FLOW, g.edgesFrom(app).get(0).kind());
    }

    @Test
    void domainToAdapterIsOutwardFlow() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(domain, adapterA)), modules);
        assertEquals(EdgeKind.OUTWARD_FLOW, g.edgesFrom(domain).get(0).kind());
    }

    @Test
    void applicationToAdapterIsOutwardFlow() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(app, adapterA)), modules);
        assertEquals(EdgeKind.OUTWARD_FLOW, g.edgesFrom(app).get(0).kind());
    }

    @Test
    void targetNotInBlueprintIsExternalLib() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, external)), modules);
        assertEquals(EdgeKind.EXTERNAL_LIB, g.edgesFrom(adapterA).get(0).kind());
    }

    @Test
    void lateralCouplingsReturnsCorrectPeers() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, adapterB), new Dependency(adapterA, app)), modules);
        Set<Module> peers = g.lateralCouplings(adapterA);
        assertEquals(Set.of(adapterB), peers);
    }

    @Test
    void hasBypassedPortReturnsTrueWhenPortExists() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, domain)), modules);
        assertTrue(g.hasBypassedPort(adapterA));
    }

    @Test
    void hasBypassedPortReturnsFalseWhenOnlyAccessingViaPort() {
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, port)), modules);
        assertFalse(g.hasBypassedPort(adapterA));
    }

    @Test
    void hasBypassedPortReturnsFalseWhenNoPortModuleExists() {
        Set<Module> noPortModules = Set.of(domain, app, adapterA, adapterB);
        DependencyGraph g = DependencyGraph.from(
                Set.of(new Dependency(adapterA, domain)), noPortModules);
        assertFalse(g.hasBypassedPort(adapterA));
    }

    @Test
    void edgesByKindFiltersCorrectly() {
        DependencyGraph g = DependencyGraph.from(Set.of(
                new Dependency(adapterA, adapterB),
                new Dependency(adapterA, app),
                new Dependency(app, domain)
        ), modules);
        List<DependencyEdge> lateral = g.edgesByKind(EdgeKind.LATERAL_COUPLING);
        assertEquals(1, lateral.size());
        assertEquals(adapterB, lateral.get(0).target());
    }

    @Test
    void layerKindResolutionUsesLayerField() {
        Module m = new Module("anything", List.of(), 0);
        assertEquals(LayerKind.DOMAIN, LayerKind.resolve(m));
    }

    @Test
    void layerKindResolutionFallsBackToNameHeuristic() {
        assertEquals(LayerKind.DOMAIN,      LayerKind.resolve(new Module("domain")));
        assertEquals(LayerKind.PORT,        LayerKind.resolve(new Module("application-port")));
        assertEquals(LayerKind.APPLICATION, LayerKind.resolve(new Module("application")));
        assertEquals(LayerKind.ADAPTER,     LayerKind.resolve(new Module("adapter-cli")));
        assertEquals(LayerKind.UNKNOWN,     LayerKind.resolve(new Module("test")));
    }

    @Test
    void layerKindExternalViaPackagePattern() {
        Module m = new Module("hibernate", List.of("org.hibernate.**"), -1);
        assertEquals(LayerKind.EXTERNAL, LayerKind.resolve(m));
    }
}
