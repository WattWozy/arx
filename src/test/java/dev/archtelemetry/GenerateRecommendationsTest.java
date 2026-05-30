package dev.archtelemetry;

import dev.archtelemetry.application.GenerateRecommendations;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DependencyGraph;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleGitStats;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Recommendation;
import dev.archtelemetry.domain.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GenerateRecommendationsTest {

    private static final Module domain   = new Module("domain",           List.of(), 0);
    private static final Module port     = new Module("application-port", List.of(), 1);
    private static final Module app      = new Module("application",      List.of(), 2);
    private static final Module adapterA = new Module("adapter-http",     List.of(), 3);
    private static final Module adapterB = new Module("adapter-db",       List.of(), 3);
    private static final Module external = new Module("ext-lib",          List.of("org.hibernate.**"), -1);

    private static final Set<Module> allModules = Set.of(domain, port, app, adapterA, adapterB);
    private static final Blueprint blueprint = new Blueprint(allModules, Set.of(), java.util.Optional.empty());

    private static final GenerateRecommendations engine = new GenerateRecommendations(20.0, 30, 5);

    // -------------------------------------------------------------------------
    // Rule 1: SPLIT
    // -------------------------------------------------------------------------

    @Test
    void rule1SplitTriggeredWhenAllConditionsMet() {
        // fanIn=1, fanOut=10 → instability ≈ 0.91; wmc=40; hotspot = 40*10 = 400
        ModuleGitStats git = new ModuleGitStats(adapterA, 10, 1, Set.of("a@x.com"), 5, 3.0);
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 1, 10, 40, git);
        DependencyGraph graph = DependencyGraph.from(Set.of(new Dependency(adapterA, app)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);

        assertTrue(recs.stream().anyMatch(r -> r.code().equals("SPLIT") && r.target().equals(adapterA)));
        assertEquals(Severity.ACTION_REQUIRED,
                recs.stream().filter(r -> r.code().equals("SPLIT")).findFirst().orElseThrow().severity());
    }

    @Test
    void rule1SplitNotTriggeredWhenHotspotTooLow() {
        // hotspot = 0 (no git stats) — below threshold 20
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 1, 10, 35, null);
        DependencyGraph graph = DependencyGraph.from(Set.of(), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("SPLIT") && r.target().equals(adapterA)));
    }

    // -------------------------------------------------------------------------
    // Rule 2: ABSTRACT — with port-fronting calibration
    // -------------------------------------------------------------------------

    @Test
    void rule2AbstractTriggeredAsWarningWhenNoPortFronts() {
        // instability < 0.3, hotspot > 20, fanIn > 5, no port has high fan-in
        ModuleGitStats git = new ModuleGitStats(domain, 10, 1, Set.of("a@x.com"), 5, 3.0);
        // fanIn=10, fanOut=1 → instability ≈ 0.09; hotspot = 5*10 = 50
        ModuleMetrics mmDomain = ModuleMetrics.compute(domain, 10, 1, 5, git);
        // port has LOW fan-in (1) — does NOT front the domain
        ModuleMetrics mmPort = ModuleMetrics.compute(port, 1, 0);
        Map<Module, ModuleMetrics> metricsMap = Map.of(domain, mmDomain, port, mmPort);

        DependencyGraph graph = DependencyGraph.from(Set.of(), allModules);
        List<Recommendation> recs = engine.generate(graph, metricsMap, blueprint);

        Recommendation r = recs.stream()
                .filter(rec -> rec.code().equals("ABSTRACT") && rec.target().equals(domain))
                .findFirst().orElseThrow(() -> new AssertionError("ABSTRACT not found"));
        assertEquals(Severity.WARNING, r.severity());
    }

    @Test
    void rule2AbstractDowngradedToInfoWhenPortAlreadyFronts() {
        // Same domain metrics but NOW port has high fan-in (> 5) → port fronts the domain
        ModuleGitStats git = new ModuleGitStats(domain, 10, 1, Set.of("a@x.com"), 5, 3.0);
        ModuleMetrics mmDomain = ModuleMetrics.compute(domain, 10, 1, 5, git);
        ModuleMetrics mmPort = ModuleMetrics.compute(port, 8, 0); // fanIn=8 > threshold of 5
        Map<Module, ModuleMetrics> metricsMap = Map.of(domain, mmDomain, port, mmPort);

        DependencyGraph graph = DependencyGraph.from(Set.of(), allModules);
        List<Recommendation> recs = engine.generate(graph, metricsMap, blueprint);

        Recommendation r = recs.stream()
                .filter(rec -> rec.code().equals("ABSTRACT") && rec.target().equals(domain))
                .findFirst().orElseThrow(() -> new AssertionError("ABSTRACT not found"));
        assertEquals(Severity.INFO, r.severity());
        assertTrue(r.summary().contains("application-port already provides"), r.summary());
    }

    @Test
    void rule2AbstractNotTriggeredWhenInstabilityTooHigh() {
        ModuleGitStats git = new ModuleGitStats(domain, 10, 1, Set.of("a@x.com"), 5, 3.0);
        // fanIn=5, fanOut=5 → instability = 0.5 (above 0.3 threshold)
        ModuleMetrics mm = ModuleMetrics.compute(domain, 5, 5, 5, git);
        DependencyGraph graph = DependencyGraph.from(Set.of(), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(domain, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("ABSTRACT") && r.target().equals(domain)));
    }

    // -------------------------------------------------------------------------
    // Rule 3: DECOUPLE_LATERAL
    // -------------------------------------------------------------------------

    @Test
    void rule3LateralTriggeredForAdapterToAdapterEdge() {
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 0, 1);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(adapterA, adapterB)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().anyMatch(r -> r.code().equals("DECOUPLE_LATERAL")
                && r.target().equals(adapterA)));
        assertEquals(Severity.ACTION_REQUIRED,
                recs.stream().filter(r -> r.code().equals("DECOUPLE_LATERAL")).findFirst()
                        .orElseThrow().severity());
    }

    @Test
    void rule3LateralNotTriggeredForNonLateralEdge() {
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 0, 1);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(adapterA, app)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("DECOUPLE_LATERAL")));
    }

    // -------------------------------------------------------------------------
    // Rule 4: DECOUPLE_PORT_BYPASS
    // Scanner has no type info → always INFO, never WARNING
    // -------------------------------------------------------------------------

    @Test
    void rule4PortBypassEmitsInfoWhenAdapterAccessesDomainAndPortExists() {
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 0, 1);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(adapterA, domain)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);

        Recommendation r = recs.stream()
                .filter(rec -> rec.code().equals("DECOUPLE_PORT_BYPASS") && rec.target().equals(adapterA))
                .findFirst().orElseThrow(() -> new AssertionError("DECOUPLE_PORT_BYPASS not found"));
        assertEquals(Severity.INFO, r.severity(), "Must be INFO — scanner cannot determine type, avoid false positives");
        assertTrue(r.summary().contains("cannot classify"), r.summary());
    }

    @Test
    void rule4PortBypassNotTriggeredWhenNoPortModuleExists() {
        Set<Module> noPort = Set.of(domain, app, adapterA, adapterB);
        Blueprint bpNoPort = new Blueprint(noPort, Set.of(), java.util.Optional.empty());
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 0, 1);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(adapterA, domain)), noPort);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), bpNoPort);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("DECOUPLE_PORT_BYPASS")));
    }

    // -------------------------------------------------------------------------
    // Rule 5: OUTWARD_FLOW
    // -------------------------------------------------------------------------

    @Test
    void rule5OutwardFlowTriggeredForDomainToAdapter() {
        ModuleMetrics mm = ModuleMetrics.compute(domain, 0, 1);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(domain, adapterA)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(domain, mm), blueprint);
        assertTrue(recs.stream().anyMatch(r -> r.code().equals("OUTWARD_FLOW")
                && r.target().equals(domain)));
        assertEquals(Severity.ACTION_REQUIRED,
                recs.stream().filter(r -> r.code().equals("OUTWARD_FLOW")).findFirst()
                        .orElseThrow().severity());
    }

    @Test
    void rule5OutwardFlowNotTriggeredForCorrectInwardDependency() {
        ModuleMetrics mm = ModuleMetrics.compute(app, 0, 1);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(app, domain)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(app, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("OUTWARD_FLOW")));
    }

    // -------------------------------------------------------------------------
    // Rule 6: SUPPRESS
    // -------------------------------------------------------------------------

    @Test
    void rule6SuppressTriggeredWhenAllFanOutIsExternal() {
        // instability=1.0 > 0.5; all deps → external
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 0, 1);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(adapterA, external)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().anyMatch(r -> r.code().equals("SUPPRESS") && r.target().equals(adapterA)));
        assertEquals(Severity.INFO,
                recs.stream().filter(r -> r.code().equals("SUPPRESS")).findFirst()
                        .orElseThrow().severity());
    }

    @Test
    void rule6SuppressTriggeredWhenAllFanOutIsPortOrApplication() {
        // adapter → port + adapter → app: correctly wired, should suppress coupling flag
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 0, 2);
        DependencyGraph graph = DependencyGraph.from(Set.of(
                new Dependency(adapterA, port),
                new Dependency(adapterA, app)
        ), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().anyMatch(r -> r.code().equals("SUPPRESS") && r.target().equals(adapterA)));
    }

    @Test
    void rule6SuppressNotTriggeredWhenSomeFanOutIsLateral() {
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 0, 2);
        DependencyGraph graph = DependencyGraph.from(Set.of(
                new Dependency(adapterA, external),
                new Dependency(adapterA, adapterB) // lateral — not safe
        ), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("SUPPRESS") && r.target().equals(adapterA)));
    }

    // -------------------------------------------------------------------------
    // Rule 7: BUS_FACTOR
    // -------------------------------------------------------------------------

    @Test
    void rule7BusFactorTriggeredWhenSingleAuthorAndHotspot() {
        // busFactorRisk = commitCount/authorCount; commits=10, authors=10 → risk=1.0
        // hotspot = wmc * commits = 5 * 10 = 50 > 20
        ModuleGitStats git = new ModuleGitStats(adapterA, 10, 10, Set.of("a@x.com"), 5, 3.0);
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 3, 3, 5, git);
        DependencyGraph graph = DependencyGraph.from(Set.of(), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().anyMatch(r -> r.code().equals("BUS_FACTOR") && r.target().equals(adapterA)));
        assertEquals(Severity.WARNING,
                recs.stream().filter(r -> r.code().equals("BUS_FACTOR")).findFirst()
                        .orElseThrow().severity());
    }

    @Test
    void rule7BusFactorNotTriggeredWhenHotspotTooLow() {
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 3, 3); // hotspot=0
        DependencyGraph graph = DependencyGraph.from(Set.of(), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("BUS_FACTOR") && r.target().equals(adapterA)));
    }

    // -------------------------------------------------------------------------
    // Rule 8: MERGE — thin module
    // -------------------------------------------------------------------------

    @Test
    void rule8MergeTriggeredForThinNonExemptModule() {
        // wmc=2 < 5, fanOut=1, fanIn=1 <= 2
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 1, 1, 2, null);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(adapterA, app)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().anyMatch(r -> r.code().equals("MERGE") && r.target().equals(adapterA)));
        assertEquals(Severity.INFO,
                recs.stream().filter(r -> r.code().equals("MERGE")).findFirst()
                        .orElseThrow().severity());
    }

    @Test
    void rule8MergeExemptedForPortModule() {
        // port with wmc=2, fanOut=1, fanIn=1 — port is exempt
        ModuleMetrics mm = ModuleMetrics.compute(port, 1, 1, 2, null);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(port, domain)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(port, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("MERGE") && r.target().equals(port)));
    }

    @Test
    void rule8MergeExemptedForDomainModule() {
        // domain module with wmc=2, fanOut=1, fanIn=1 — domain is exempt
        Module domainSub = new Module("domain-events", List.of(), 0);
        Set<Module> mods = Set.of(domainSub, app, adapterA);
        Blueprint bp = new Blueprint(mods, Set.of(), java.util.Optional.empty());
        ModuleMetrics mm = ModuleMetrics.compute(domainSub, 1, 1, 2, null);
        DependencyGraph graph = DependencyGraph.from(Set.of(), mods);
        List<Recommendation> recs = engine.generate(graph, Map.of(domainSub, mm), bp);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("MERGE") && r.target().equals(domainSub)));
    }

    @Test
    void rule8MergeNotTriggeredWhenWmcTooHigh() {
        // wmc=5 is exactly the threshold — not triggered (>= 5 skips the rule)
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 1, 1, 5, null);
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(adapterA, app)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("MERGE") && r.target().equals(adapterA)));
    }

    @Test
    void rule8MergeNotTriggeredWhenFanOutIsNotOne() {
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 1, 2, 2, null); // fanOut=2
        DependencyGraph graph = DependencyGraph.from(
                Set.of(new Dependency(adapterA, app), new Dependency(adapterA, port)), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().noneMatch(r -> r.code().equals("MERGE") && r.target().equals(adapterA)));
    }

    // -------------------------------------------------------------------------
    // Compound: SPLIT + DECOUPLE_LATERAL → merged, constituent codes gone
    // -------------------------------------------------------------------------

    @Test
    void compoundSplitAndLateralProducesMergedRecommendation() {
        ModuleGitStats git = new ModuleGitStats(adapterA, 10, 1, Set.of("a@x.com"), 5, 3.0);
        ModuleMetrics mm = ModuleMetrics.compute(adapterA, 1, 10, 40, git); // instability ≈ 0.91
        DependencyGraph graph = DependencyGraph.from(Set.of(
                new Dependency(adapterA, adapterB),
                new Dependency(adapterA, app)
        ), allModules);
        List<Recommendation> recs = engine.generate(graph, Map.of(adapterA, mm), blueprint);
        assertTrue(recs.stream().anyMatch(r -> r.code().equals("SPLIT+DECOUPLE_LATERAL")));
        assertFalse(recs.stream().anyMatch(r -> r.code().equals("SPLIT") && r.target().equals(adapterA)));
        assertFalse(recs.stream().anyMatch(r -> r.code().equals("DECOUPLE_LATERAL") && r.target().equals(adapterA)));
    }

    // -------------------------------------------------------------------------
    // Output ordering: ACTION_REQUIRED before WARNING before INFO
    // -------------------------------------------------------------------------

    @Test
    void recommendationsSortedBySeverityDescending() {
        ModuleGitStats git = new ModuleGitStats(adapterA, 10, 1, Set.of("a@x.com"), 5, 3.0);
        ModuleMetrics mmA = ModuleMetrics.compute(adapterA, 1, 10, 40, git);
        ModuleMetrics mmDomain = ModuleMetrics.compute(domain, 0, 1);
        Map<Module, ModuleMetrics> metricsMap = Map.of(adapterA, mmA, domain, mmDomain);
        DependencyGraph graph = DependencyGraph.from(Set.of(
                new Dependency(adapterA, app),
                new Dependency(domain, adapterA)
        ), allModules);
        List<Recommendation> recs = engine.generate(graph, metricsMap, blueprint);
        assertFalse(recs.isEmpty());
        for (int i = 1; i < recs.size(); i++) {
            assertTrue(recs.get(i - 1).severity().ordinal() >= recs.get(i).severity().ordinal(),
                    "Not sorted at index " + i + ": " + recs.get(i - 1).severity()
                            + " vs " + recs.get(i).severity());
        }
    }
}
