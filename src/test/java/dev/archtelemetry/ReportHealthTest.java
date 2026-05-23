package dev.archtelemetry;

import dev.archtelemetry.application.AnalyzeHistory;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.application.ReportHealth;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.Violation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportHealthTest {

    private final AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
    private final AnalyzeHistory analyzeHistory = new AnalyzeHistory(analyzeSnapshot);
    private final ReportHealth reportHealth = new ReportHealth();

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
    void reportIdentifiesNewAndResolvedViolationsBetweenConsecutiveSnapshots() {
        Dependency persistent = new Dependency(domain, infrastructure);
        Dependency resolved = new Dependency(application, infrastructure);
        Dependency introduced = new Dependency(infrastructure, domain);

        Trend trend = analyzeHistory.analyze(noRules, List.of(
                snapshotWith("c1", persistent, resolved),
                snapshotWith("c2", persistent, introduced)
        ));

        HealthReport report = reportHealth.report(trend);

        assertEquals(2, report.totalViolations());
        assertEquals(Set.of(new Violation(introduced)), report.newViolations());
        assertEquals(Set.of(new Violation(resolved)), report.resolvedViolations());
    }
}
