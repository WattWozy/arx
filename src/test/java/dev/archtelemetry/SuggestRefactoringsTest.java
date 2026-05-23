package dev.archtelemetry;

import dev.archtelemetry.application.SuggestRefactorings;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.RefactoringSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestRefactoringsTest {

    private final SuggestRefactorings useCase = new SuggestRefactorings();

    private static Module mod(String name) { return new Module(name); }

    private static ModuleMetrics metrics(Module m, int fanIn, int fanOut, int wmc) {
        return ModuleMetrics.compute(m, fanIn, fanOut, wmc, null, 0.0);
    }

    @Test
    void largeLowCohesionModuleFlaggedForSplit() {
        Module big = mod("bigModule");
        // wmc=35 > 30, fanOut=3 => cohesion=1/4=0.25 < 0.4
        Set<ModuleMetrics> m = Set.of(metrics(big, 0, 3, 35));
        Set<Dependency> deps = Set.of(
                new Dependency(big, mod("a")),
                new Dependency(big, mod("b")),
                new Dependency(big, mod("c")));

        List<RefactoringSuggestion> suggestions = useCase.suggest(m, deps);

        assertEquals(1, suggestions.size());
        assertEquals(RefactoringSuggestion.Type.SPLIT, suggestions.get(0).type());
        assertEquals(big, suggestions.get(0).module());
    }

    @Test
    void tinyModuleWithSinglePartnerFlaggedForMerge() {
        Module small = mod("tinyModule");
        Module partner = mod("partner");
        // wmc=3 < 5, fanOut=1
        Set<ModuleMetrics> m = Set.of(metrics(small, 0, 1, 3));
        Set<Dependency> deps = Set.of(new Dependency(small, partner));

        List<RefactoringSuggestion> suggestions = useCase.suggest(m, deps);

        assertEquals(1, suggestions.size());
        assertEquals(RefactoringSuggestion.Type.MERGE, suggestions.get(0).type());
        assertTrue(suggestions.get(0).reason().contains(partner.name()));
    }

    @Test
    void healthyModuleProducesNoSuggestions() {
        Module healthy = mod("service");
        // wmc=15, fanOut=1 — not flagged (wmc not > 30)
        Set<ModuleMetrics> m = Set.of(metrics(healthy, 2, 1, 15));
        Set<Dependency> deps = Set.of(new Dependency(healthy, mod("dep")));

        List<RefactoringSuggestion> suggestions = useCase.suggest(m, deps);

        assertTrue(suggestions.isEmpty());
    }

    @Test
    void emptyModuleWithNoWmcNotFlaggedForMerge() {
        Module empty = mod("empty");
        // wmc=0 — excluded from merge (zero wmc means no code)
        Set<ModuleMetrics> m = Set.of(metrics(empty, 0, 1, 0));
        Set<Dependency> deps = Set.of(new Dependency(empty, mod("x")));

        List<RefactoringSuggestion> suggestions = useCase.suggest(m, deps);

        assertTrue(suggestions.isEmpty());
    }
}
