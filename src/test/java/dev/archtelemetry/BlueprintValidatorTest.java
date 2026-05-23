package dev.archtelemetry;

import dev.archtelemetry.application.BlueprintValidator;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.StaleModuleWarning;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintValidatorTest {

    private final BlueprintValidator validator = new BlueprintValidator();

    @Test
    void moduleWithNoFilesAndNoDepsIsStale() {
        Module a = new Module("A");
        Module b = new Module("B");
        Blueprint blueprint = new Blueprint(Set.of(a, b), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(), Map.of(a, 3));

        List<StaleModuleWarning> warnings = validator.validate(blueprint, snapshot);

        assertEquals(1, warnings.size());
        assertEquals(b, warnings.get(0).module());
    }

    @Test
    void moduleWithWmcEntryIsNotStale() {
        Module a = new Module("A");
        Blueprint blueprint = new Blueprint(Set.of(a), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(), Map.of(a, 0));

        List<StaleModuleWarning> warnings = validator.validate(blueprint, snapshot);

        assertTrue(warnings.isEmpty());
    }

    @Test
    void moduleAsDependencySourceIsNotStale() {
        Module a = new Module("A");
        Module b = new Module("B");
        Blueprint blueprint = new Blueprint(Set.of(a, b), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(),
                Set.of(new Dependency(a, b)), Map.of());

        List<StaleModuleWarning> warnings = validator.validate(blueprint, snapshot);

        // b is a target only (no files, not a source) → stale; a is a source → not stale
        assertEquals(1, warnings.size());
        assertEquals(b, warnings.get(0).module());
    }

    @Test
    void emptyBlueprintProducesNoWarnings() {
        Blueprint blueprint = new Blueprint(Set.of(), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of());

        List<StaleModuleWarning> warnings = validator.validate(blueprint, snapshot);

        assertTrue(warnings.isEmpty());
    }

    @Test
    void allActiveModulesProduceNoWarnings() {
        Module a = new Module("A");
        Module b = new Module("B");
        Blueprint blueprint = new Blueprint(Set.of(a, b), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(),
                Set.of(new Dependency(a, b)), Map.of(b, 5));

        List<StaleModuleWarning> warnings = validator.validate(blueprint, snapshot);

        assertTrue(warnings.isEmpty());
    }
}
