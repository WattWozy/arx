package dev.archtelemetry;

import dev.archtelemetry.application.AnalyzeHistory;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DriftDirection;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Trend;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalyzeHistoryTest {

    private final AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
    private final AnalyzeHistory analyzeHistory = new AnalyzeHistory(analyzeSnapshot);

    private final Module domain = new Module("domain");
    private final Module application = new Module("application");
    private final Module infrastructure = new Module("infrastructure");

    private final Blueprint noRules = new Blueprint(
            Set.of(domain, application, infrastructure),
            Set.of()
    );

    private Snapshot snapshotWith(String commitId, Dependency... deps) {
        return new Snapshot(commitId, Instant.now(), Set.of(deps));
    }

    @Test
    void improvingViolationCountProducesImprovingTrend() {
        Dependency d1 = new Dependency(domain, infrastructure);
        Dependency d2 = new Dependency(application, infrastructure);
        Dependency d3 = new Dependency(infrastructure, domain);

        Trend trend = analyzeHistory.analyze(noRules, List.of(
                snapshotWith("c1", d1, d2, d3),
                snapshotWith("c2", d1, d2),
                snapshotWith("c3", d1)
        ));

        assertEquals(DriftDirection.IMPROVING, trend.direction());
    }

    @Test
    void stableViolationCountProducesStableTrend() {
        Dependency d1 = new Dependency(domain, infrastructure);
        Dependency d2 = new Dependency(application, infrastructure);
        Dependency d3 = new Dependency(infrastructure, domain);

        Trend trend = analyzeHistory.analyze(noRules, List.of(
                snapshotWith("c1", d1, d2, d3),
                snapshotWith("c2", d1, d2, d3),
                snapshotWith("c3", d1, d2, d3)
        ));

        assertEquals(DriftDirection.STABLE, trend.direction());
    }

    @Test
    void worseningViolationCountProducesDegradingTrend() {
        Dependency d1 = new Dependency(domain, infrastructure);
        Dependency d2 = new Dependency(application, infrastructure);
        Dependency d3 = new Dependency(infrastructure, domain);

        Trend trend = analyzeHistory.analyze(noRules, List.of(
                snapshotWith("c1", d1),
                snapshotWith("c2", d1, d2),
                snapshotWith("c3", d1, d2, d3)
        ));

        assertEquals(DriftDirection.DEGRADING, trend.direction());
    }
}
