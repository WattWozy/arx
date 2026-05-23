package dev.archtelemetry.application;

import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.RefactoringSuggestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class SuggestRefactorings {

    // Cohesion proxy: 1/(1+fanOut). Below 0.4 means fanOut >= 2.
    private static final double COHESION_THRESHOLD = 0.4;
    private static final int HIGH_WMC = 30;
    private static final int LOW_WMC = 5;

    public List<RefactoringSuggestion> suggest(Set<ModuleMetrics> metrics, Set<Dependency> deps) {
        Map<Module, Set<Module>> outgoing = buildOutgoingMap(deps);
        List<RefactoringSuggestion> suggestions = new ArrayList<>();

        for (ModuleMetrics m : metrics) {
            double cohesion = 1.0 / (1.0 + m.fanOut());

            if (m.wmc() > HIGH_WMC && cohesion < COHESION_THRESHOLD) {
                suggestions.add(new RefactoringSuggestion(m.module(),
                        RefactoringSuggestion.Type.SPLIT,
                        "wmc=" + m.wmc() + ", fanOut=" + m.fanOut()
                        + " — high complexity with many outgoing dependencies; split into cohesive sub-modules"));
            } else if (m.wmc() > 0 && m.wmc() < LOW_WMC && m.fanOut() == 1) {
                Set<Module> targets = outgoing.getOrDefault(m.module(), Set.of());
                String partner = targets.stream().map(Module::name).findFirst().orElse("unknown");
                suggestions.add(new RefactoringSuggestion(m.module(),
                        RefactoringSuggestion.Type.MERGE,
                        "wmc=" + m.wmc() + " with single outgoing dependency on " + partner
                        + " — thin module tightly coupled to one partner; consider merging"));
            }
        }

        suggestions.sort((a, b) -> a.module().name().compareTo(b.module().name()));
        return List.copyOf(suggestions);
    }

    private Map<Module, Set<Module>> buildOutgoingMap(Set<Dependency> deps) {
        Map<Module, Set<Module>> map = new HashMap<>();
        for (Dependency dep : deps) {
            map.computeIfAbsent(dep.source(), k -> new HashSet<>()).add(dep.target());
        }
        return map;
    }
}
