package dev.archtelemetry;

import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Violation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzeSnapshotTest {

    private final AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();

    private final Module domain = new Module("domain");
    private final Module application = new Module("application");
    private final Module infrastructure = new Module("infrastructure");

    @Test
    void allowedDependencyProducesNoViolation() {
        Dependency allowed = new Dependency(application, domain);
        Blueprint blueprint = new Blueprint(
                Set.of(domain, application),
                Set.of(allowed)
        );
        Snapshot snapshot = new Snapshot("abc123", Instant.now(), Set.of(allowed));

        Set<Violation> violations = analyzeSnapshot.analyze(blueprint, snapshot);

        assertTrue(violations.isEmpty());
    }

    @Test
    void forbiddenDependencyProducesViolation() {
        Dependency forbidden = new Dependency(domain, infrastructure);
        Blueprint blueprint = new Blueprint(
                Set.of(domain, infrastructure),
                Set.of()
        );
        Snapshot snapshot = new Snapshot("abc123", Instant.now(), Set.of(forbidden));

        Set<Violation> violations = analyzeSnapshot.analyze(blueprint, snapshot);

        assertEquals(1, violations.size());
        assertEquals(forbidden, violations.iterator().next().dependency());
    }
}
