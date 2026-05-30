package dev.archtelemetry.application;

import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.DependencyEdge;
import dev.archtelemetry.domain.DependencyGraph;
import dev.archtelemetry.domain.EdgeKind;
import dev.archtelemetry.domain.LayerKind;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Recommendation;
import dev.archtelemetry.domain.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GenerateRecommendations {

    private final double hotspotThreshold;
    private final int wmcSplitThreshold;
    private final int fanInAbstractThreshold;

    public GenerateRecommendations() {
        this(20.0, 30, 5);
    }

    public GenerateRecommendations(double hotspotThreshold, int wmcSplitThreshold, int fanInAbstractThreshold) {
        this.hotspotThreshold = hotspotThreshold;
        this.wmcSplitThreshold = wmcSplitThreshold;
        this.fanInAbstractThreshold = fanInAbstractThreshold;
    }

    public List<Recommendation> generate(DependencyGraph graph, Map<Module, ModuleMetrics> metrics,
                                          Blueprint blueprint) {
        Map<Module, List<Recommendation>> byModule = new HashMap<>();

        for (Module module : blueprint.modules()) {
            ModuleMetrics mm = metrics.get(module);
            if (mm == null) continue;
            List<Recommendation> recs = new ArrayList<>();

            applyRuleSplit(module, mm, graph, recs);
            applyRuleAbstract(module, mm, graph, metrics, recs);
            applyRuleDecoupleLateral(module, graph, recs);
            applyRuleDecouplePortBypass(module, graph, blueprint, recs);
            applyRuleOutwardFlow(module, graph, recs);
            applyRuleSuppress(module, mm, graph, recs);
            applyRuleBusFactor(module, mm, recs);
            applyRuleMerge(module, mm, graph, recs);

            if (!recs.isEmpty()) byModule.put(module, recs);
        }

        List<Recommendation> merged = mergeAndCompound(byModule);

        merged.sort(Comparator
                .comparing((Recommendation r) -> r.severity().ordinal())
                .reversed()
                .thenComparing(r -> r.target().name()));
        return merged;
    }

    // -------------------------------------------------------------------------
    // Rule 1: SPLIT — volatile complex module
    // -------------------------------------------------------------------------

    private void applyRuleSplit(Module module, ModuleMetrics mm, DependencyGraph graph,
                                 List<Recommendation> out) {
        if (mm.hotspot() <= hotspotThreshold || mm.instability() <= 0.5 || mm.wmc() <= wmcSplitThreshold) return;
        List<DependencyEdge> evidence = graph.edgesFrom(module);
        out.add(new Recommendation(module, Severity.ACTION_REQUIRED, "SPLIT",
                String.format("Split this module. It's complex (WMC=%d), volatile (hotspot=%.1f), "
                        + "and unstable (I=%.2f). Extract sub-modules by responsibility.",
                        mm.wmc(), mm.hotspot(), mm.instability()),
                String.format("WMC=%d exceeds split threshold of %d, hotspot=%.1f exceeds %.1f, "
                        + "and instability=%.2f > 0.5. This module is simultaneously hard to change (high WMC), "
                        + "changes frequently (high hotspot), and unstable. "
                        + "Split along its dependency fan-out boundaries to reduce blast radius.",
                        mm.wmc(), wmcSplitThreshold, mm.hotspot(), hotspotThreshold, mm.instability()),
                evidence));
    }

    // -------------------------------------------------------------------------
    // Rule 2: ABSTRACT — stable painful module
    // Calibration: if a PORT module already fronts this module (port exists and has
    // high fan-in), the abstraction layer already exists — downgrade to INFO.
    // -------------------------------------------------------------------------

    private void applyRuleAbstract(Module module, ModuleMetrics mm, DependencyGraph graph,
                                    Map<Module, ModuleMetrics> allMetrics, List<Recommendation> out) {
        if (mm.instability() >= 0.3 || mm.hotspot() <= hotspotThreshold || mm.fanIn() <= fanInAbstractThreshold)
            return;
        List<DependencyEdge> evidence = graph.edgesTo(module);

        // Check whether a PORT module already provides the abstraction boundary.
        // If a port module with high fan-in exists, dependents already code against
        // the interface layer — the domain being concrete is fine.
        boolean portAlreadyFronts = allMetrics.entrySet().stream()
                .anyMatch(e -> LayerKind.resolve(e.getKey()) == LayerKind.PORT
                        && e.getValue().fanIn() > fanInAbstractThreshold);

        if (portAlreadyFronts) {
            out.add(new Recommendation(module, Severity.INFO, "ABSTRACT",
                    "Domain is concrete but application-port already provides the abstraction layer. "
                    + "Current structure is correct.",
                    String.format("fanIn=%d and hotspot=%.1f would normally suggest extracting interfaces, "
                            + "but a PORT module with high fan-in already acts as the interface boundary. "
                            + "Dependents should code against the port, not the domain directly.",
                            mm.fanIn(), mm.hotspot()),
                    evidence));
        } else {
            out.add(new Recommendation(module, Severity.WARNING, "ABSTRACT",
                    String.format("Extract interfaces from this module. It has %d dependents but is all-concrete, "
                            + "making it painful to change. Define abstractions (ports/interfaces) that "
                            + "dependents can code against.", mm.fanIn()),
                    String.format("fanIn=%d exceeds %d, instability=%.2f < 0.3 (stable), and hotspot=%.1f > %.1f. "
                            + "Many modules depend on this concrete module which changes frequently. "
                            + "Introducing interfaces decouples dependents from its implementation.",
                            mm.fanIn(), fanInAbstractThreshold, mm.instability(), mm.hotspot(), hotspotThreshold),
                    evidence));
        }
    }

    // -------------------------------------------------------------------------
    // Rule 3: DECOUPLE_LATERAL — adapter-to-adapter coupling
    // -------------------------------------------------------------------------

    private void applyRuleDecoupleLateral(Module module, DependencyGraph graph,
                                           List<Recommendation> out) {
        List<DependencyEdge> lateralEdges = graph.edgesFrom(module).stream()
                .filter(e -> e.kind() == EdgeKind.LATERAL_COUPLING)
                .toList();
        for (DependencyEdge edge : lateralEdges) {
            out.add(new Recommendation(module, Severity.ACTION_REQUIRED, "DECOUPLE_LATERAL",
                    String.format("Module '%s' depends directly on '%s', but both are adapters. "
                            + "Route this through a port or the application layer.",
                            module.name(), edge.target().name()),
                    String.format("Lateral adapter coupling creates tight coupling between I/O concerns. "
                            + "'%s' -> '%s' should be mediated by an application use case or port interface "
                            + "so adapters remain independently replaceable.",
                            module.name(), edge.target().name()),
                    List.of(edge)));
        }
    }

    // -------------------------------------------------------------------------
    // Rule 4: DECOUPLE_PORT_BYPASS — adapter calling domain logic directly
    //
    // Calibration: the scanner does not capture whether a domain type is a
    // value object (record/enum) or a behavioral class. Since false positives
    // erode trust, we never emit WARNING here. We emit INFO to signal that
    // domain access was observed but the nature of the coupling is unknown.
    // When type metadata is available in the scanner, this rule can be upgraded.
    // -------------------------------------------------------------------------

    private void applyRuleDecouplePortBypass(Module module, DependencyGraph graph,
                                              Blueprint blueprint, List<Recommendation> out) {
        if (!graph.hasBypassedPort(module)) return;
        List<DependencyEdge> domainEdges = graph.edgesFrom(module).stream()
                .filter(e -> e.kind() == EdgeKind.DOMAIN_ACCESS)
                .toList();
        String portName = blueprint.modules().stream()
                .filter(m -> LayerKind.resolve(m) == LayerKind.PORT)
                .map(Module::name)
                .sorted()
                .findFirst()
                .orElse("port module");
        out.add(new Recommendation(module, Severity.INFO, "DECOUPLE_PORT_BYPASS",
                String.format("Domain access detected in '%s' but cannot classify as type-reference vs "
                        + "behavioral — consider adding type metadata to the scanner. "
                        + "(Domain value objects like records/enums are fine to reference directly; "
                        + "behavioral logic should route through '%s'.)",
                        module.name(), portName),
                String.format("'%s' has direct domain dependencies. In hexagonal architecture, adapters "
                        + "should communicate behavioral logic only through port interfaces. "
                        + "References to domain records/enums are acceptable shared vocabulary. "
                        + "Without type metadata the scanner cannot distinguish these cases.",
                        module.name()),
                domainEdges));
    }

    // -------------------------------------------------------------------------
    // Rule 5: OUTWARD_FLOW — dependency direction violation
    // -------------------------------------------------------------------------

    private void applyRuleOutwardFlow(Module module, DependencyGraph graph,
                                       List<Recommendation> out) {
        List<DependencyEdge> outwardEdges = graph.edgesFrom(module).stream()
                .filter(e -> e.kind() == EdgeKind.OUTWARD_FLOW)
                .toList();
        for (DependencyEdge edge : outwardEdges) {
            out.add(new Recommendation(module, Severity.ACTION_REQUIRED, "OUTWARD_FLOW",
                    String.format("Module '%s' in the inner layer depends on '%s' in an outer layer. "
                            + "This inverts the dependency direction. Use dependency inversion "
                            + "(inject via port interface).",
                            module.name(), edge.target().name()),
                    String.format("'%s' -> '%s' flows from a more stable inner layer toward a less stable "
                            + "outer layer. This forces inner-layer changes whenever the outer layer changes, "
                            + "violating the Dependency Inversion Principle. Define a port interface in the "
                            + "inner layer and inject the adapter via that interface.",
                            module.name(), edge.target().name()),
                    List.of(edge)));
        }
    }

    // -------------------------------------------------------------------------
    // Rule 6: SUPPRESS — coupling flag is misleading
    //
    // Suppress when all outgoing edges are EXTERNAL_LIB, PORT_ACCESS, or INWARD_FLOW.
    // An adapter with several dependencies that are all correctly directed (→port,
    // →application, →external) is healthy, not "highly coupled".
    // -------------------------------------------------------------------------

    private void applyRuleSuppress(Module module, ModuleMetrics mm, DependencyGraph graph,
                                    List<Recommendation> out) {
        if (mm.instability() <= 0.5 || mm.fanOut() == 0) return;

        List<DependencyEdge> outgoing = graph.edgesFrom(module);
        if (outgoing.isEmpty()) return;

        boolean allSafe = outgoing.stream().allMatch(e ->
                e.kind() == EdgeKind.EXTERNAL_LIB
                || e.kind() == EdgeKind.PORT_ACCESS
                || e.kind() == EdgeKind.INWARD_FLOW);
        if (!allSafe) return;

        boolean anyExternal = outgoing.stream().anyMatch(e -> e.kind() == EdgeKind.EXTERNAL_LIB);
        boolean anyInternal = outgoing.stream().anyMatch(e ->
                e.kind() == EdgeKind.PORT_ACCESS || e.kind() == EdgeKind.INWARD_FLOW);

        String reason = anyExternal && anyInternal
                ? "All fan-out goes to external libraries and correctly-wired inward dependencies."
                : anyExternal
                  ? "All fan-out goes to external libraries."
                  : "All fan-out goes to port or application layer (correctly wired adapter).";

        out.add(new Recommendation(module, Severity.INFO, "SUPPRESS",
                "Coupling flag suppressed. " + reason + " This is expected for an adapter.",
                String.format("'%s' has instability=%.2f and fan-out=%d, which triggers a high-coupling flag. "
                        + "However, %s Adapter-level fan-out to safe destinations is normal.",
                        module.name(), mm.instability(), mm.fanOut(), reason),
                outgoing));
    }

    // -------------------------------------------------------------------------
    // Rule 7: BUS_FACTOR — knowledge concentration risk
    // -------------------------------------------------------------------------

    private void applyRuleBusFactor(Module module, ModuleMetrics mm, List<Recommendation> out) {
        if (mm.busFactorRisk() > 1.0 || mm.hotspot() <= hotspotThreshold) return;
        out.add(new Recommendation(module, Severity.WARNING, "BUS_FACTOR",
                "Only one contributor has touched this hot module. "
                + "Spread knowledge by pairing or code review.",
                String.format("busFactorRisk=%.2f means nearly all commits come from a single author, "
                        + "yet hotspot=%.1f > %.1f shows this module changes frequently. "
                        + "If that person is unavailable the team loses institutional knowledge of "
                        + "this critical area.",
                        mm.busFactorRisk(), mm.hotspot(), hotspotThreshold),
                List.of()));
    }

    // -------------------------------------------------------------------------
    // Rule 8: MERGE — thin module with single dependency
    //
    // Layer exemptions: never suggest merging PORT modules (thin is their ideal
    // shape) or DOMAIN modules (stability matters, not size).
    // -------------------------------------------------------------------------

    private void applyRuleMerge(Module module, ModuleMetrics mm, DependencyGraph graph,
                                 List<Recommendation> out) {
        LayerKind kind = LayerKind.resolve(module);
        if (kind == LayerKind.PORT || kind == LayerKind.DOMAIN) return;
        if (mm.wmc() >= 5 || mm.fanOut() != 1 || mm.fanIn() > 2) return;

        List<DependencyEdge> outgoing = graph.edgesFrom(module);
        String target = outgoing.isEmpty() ? "its dependency" : outgoing.get(0).target().name();
        out.add(new Recommendation(module, Severity.INFO, "MERGE",
                String.format("Module '%s' has very little logic (WMC=%d) and depends only on '%s'. "
                        + "Consider merging into '%s' or into the module that consumes it.",
                        module.name(), mm.wmc(), target, target),
                String.format("WMC=%d < 5, fanOut=1, fanIn=%d <= 2. A module this thin with a single "
                        + "outgoing dependency adds indirection without encapsulation value.",
                        mm.wmc(), mm.fanIn()),
                outgoing));
    }

    // -------------------------------------------------------------------------
    // Compound recommendation merging
    // The merged recommendation REPLACES its constituents for that module.
    // Other rules for the same module are still emitted separately.
    // -------------------------------------------------------------------------

    private List<Recommendation> mergeAndCompound(Map<Module, List<Recommendation>> byModule) {
        List<Recommendation> result = new ArrayList<>();

        for (Map.Entry<Module, List<Recommendation>> entry : byModule.entrySet()) {
            Module module = entry.getKey();
            List<Recommendation> recs = entry.getValue();

            boolean hasSplit   = recs.stream().anyMatch(r -> r.code().equals("SPLIT"));
            boolean hasLateral = recs.stream().anyMatch(r -> r.code().equals("DECOUPLE_LATERAL"));
            boolean hasAbstract = recs.stream().anyMatch(r -> r.code().equals("ABSTRACT"));
            boolean hasBus      = recs.stream().anyMatch(r -> r.code().equals("BUS_FACTOR"));

            if (hasSplit && hasLateral) {
                Recommendation split   = findFirst(recs, "SPLIT");
                Recommendation lateral = findFirst(recs, "DECOUPLE_LATERAL");
                result.add(new Recommendation(module, higher(split.severity(), lateral.severity()),
                        "SPLIT+DECOUPLE_LATERAL",
                        "This module is both complex and laterally coupled. "
                        + "First extract the lateral dependency into a port, then split the remaining responsibilities.",
                        split.rationale() + " " + lateral.rationale(),
                        combined(split, lateral)));
                recs.stream()
                        .filter(r -> !r.code().equals("SPLIT") && !r.code().equals("DECOUPLE_LATERAL"))
                        .forEach(result::add);
                continue;
            }

            if (hasAbstract && hasBus) {
                Recommendation abs = findFirst(recs, "ABSTRACT");
                Recommendation bus = findFirst(recs, "BUS_FACTOR");
                result.add(new Recommendation(module, higher(abs.severity(), bus.severity()),
                        "ABSTRACT+BUS_FACTOR",
                        "This module needs interfaces AND more contributors. "
                        + "Extracting interfaces is the first step — it creates natural pairing/review opportunities.",
                        abs.rationale() + " " + bus.rationale(),
                        abs.evidence()));
                recs.stream()
                        .filter(r -> !r.code().equals("ABSTRACT") && !r.code().equals("BUS_FACTOR"))
                        .forEach(result::add);
                continue;
            }

            result.addAll(recs);
        }

        return result;
    }

    private static Recommendation findFirst(List<Recommendation> recs, String code) {
        return recs.stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    private static List<DependencyEdge> combined(Recommendation a, Recommendation b) {
        List<DependencyEdge> merged = new ArrayList<>(a.evidence());
        merged.addAll(b.evidence());
        return merged;
    }

    private static Severity higher(Severity a, Severity b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
