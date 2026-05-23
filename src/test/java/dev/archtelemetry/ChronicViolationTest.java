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
import dev.archtelemetry.domain.ViolationRecord;
import dev.archtelemetry.domain.Violation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChronicViolationTest {

    private final AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
    private final AnalyzeHistory analyzeHistory = new AnalyzeHistory(analyzeSnapshot);
    private final ReportHealth reportHealth = new ReportHealth();

    private final Module domain = new Module("domain");
    private final Module infra = new Module("infra");
    private final Blueprint noRules = new Blueprint(Set.of(domain, infra), Set.of());
    private final Dependency forbidden = new Dependency(domain, infra);

    private Snapshot snap(String id, Dependency... deps) {
        return new Snapshot(id, Instant.now(), Set.of(deps));
    }

    @Test
    void violationPresentInThreeConsecutiveSnapshotsIsMarkedChronic() {
        Trend trend = analyzeHistory.analyze(noRules, List.of(
                snap("c1", forbidden),
                snap("c2", forbidden),
                snap("c3", forbidden)
        ));

        HealthReport report = reportHealth.report(trend);

        List<ViolationRecord> records = report.violationRecords();
        assertEquals(1, records.size());
        assertTrue(records.get(0).isChronic());
        assertEquals(3, records.get(0).ageInSnapshots());
    }

    @Test
    void violationPresentInOnlyTwoSnapshotsIsNotChronic() {
        Trend trend = analyzeHistory.analyze(noRules, List.of(
                snap("c1", forbidden),
                snap("c2", forbidden)
        ));

        HealthReport report = reportHealth.report(trend);

        List<ViolationRecord> records = report.violationRecords();
        assertEquals(1, records.size());
        assertFalse(records.get(0).isChronic());
        assertEquals(2, records.get(0).ageInSnapshots());
    }

    @Test
    void violationThatDisappearedAndReturnedRestartsAge() {
        Trend trend = analyzeHistory.analyze(noRules, List.of(
                snap("c1", forbidden),
                snap("c2"),           // violation absent
                snap("c3", forbidden),
                snap("c4", forbidden)
        ));

        HealthReport report = reportHealth.report(trend);

        List<ViolationRecord> records = report.violationRecords();
        assertEquals(1, records.size());
        assertFalse(records.get(0).isChronic());
        assertEquals(2, records.get(0).ageInSnapshots());
    }

    @Test
    void noViolationsProducesEmptyViolationRecords() {
        Trend trend = analyzeHistory.analyze(noRules, List.of(
                snap("c1"),
                snap("c2")
        ));

        HealthReport report = reportHealth.report(trend);

        assertTrue(report.violationRecords().isEmpty());
    }
}
