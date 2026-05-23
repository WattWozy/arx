package dev.archtelemetry;

import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.application.ReportHealth;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Trend;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstabilityWarningTest {

    private final ReportHealth reportHealth = new ReportHealth();

    private HealthReport reportWithModule(Module module, int fanIn, int fanOut) {
        ModuleMetrics metrics = ModuleMetrics.compute(module, fanIn, fanOut);
        ArchitectureProfile profile = new ArchitectureProfile(Set.of(metrics), Set.of(), Set.of());
        Trend trend = new Trend(List.of(new Trend.SnapshotEntry("c1", Set.of())));
        return reportHealth.report(trend, List.of(profile));
    }

    @Test
    void warningFiresForInnerLayerModuleWithHighInstability() {
        // layer=0 (inner), fanIn=0 fanOut=3 → instability=1.0 > 0.5
        Module inner = new Module("domain", List.of(), 0);
        HealthReport report = reportWithModule(inner, 0, 3);
        assertFalse(report.instabilityWarnings().isEmpty());
    }

    @Test
    void warningDoesNotFireForOuterLayerModuleWithHighInstability() {
        // layer=2 (outer), fanIn=0 fanOut=3 → instability=1.0 > 0.5 but outer = OK
        Module outer = new Module("infrastructure", List.of(), 2);
        HealthReport report = reportWithModule(outer, 0, 3);
        assertTrue(report.instabilityWarnings().isEmpty());
    }
}
