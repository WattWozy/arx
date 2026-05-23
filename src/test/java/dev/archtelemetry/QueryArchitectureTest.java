package dev.archtelemetry;

import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.application.QueryArchitecture;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DriftDirection;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.ComputeMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryArchitectureTest {

    @Test
    void queryIncludesModuleNamesInPrompt() {
        Module domain = new Module("domain");
        Module app = new Module("application");
        Blueprint blueprint = new Blueprint(Set.of(domain, app), Set.of(new Dependency(app, domain)));
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(new Dependency(app, domain)));
        ArchitectureProfile profile = new ComputeMetrics(new AnalyzeSnapshot()).compute(blueprint, snapshot);

        HealthReport report = new HealthReport(
                0, Set.of(), Set.of(), DriftDirection.STABLE, 1,
                profile, List.of(), List.of(), List.of());

        String capturedPrompt = capturePrompt(report, profile, "which modules are highest risk?");

        assertTrue(capturedPrompt.contains("domain"), "Prompt should include module name 'domain'");
        assertTrue(capturedPrompt.contains("application"), "Prompt should include module name 'application'");
        assertTrue(capturedPrompt.contains("which modules are highest risk"),
                "Prompt should include the user's question");
    }

    @Test
    void queryIncludesDriftAndViolations() {
        Module domain = new Module("domain");
        Module app = new Module("application");
        Blueprint blueprint = new Blueprint(Set.of(domain, app), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(),
                Set.of(new Dependency(domain, app))); // violation: domain -> app not allowed
        ArchitectureProfile profile = new ComputeMetrics(new AnalyzeSnapshot()).compute(blueprint, snapshot);

        HealthReport report = new HealthReport(
                1, Set.of(), Set.of(), DriftDirection.DEGRADING, 1,
                profile, List.of(), List.of(), List.of());

        String prompt = capturePrompt(report, profile, "what is wrong?");

        assertTrue(prompt.contains("DEGRADING"), "Prompt should mention drift direction");
        assertTrue(prompt.contains("domain"), "Prompt should include violation source");
    }

    @Test
    void queryReturnsLlmResponse() {
        Module domain = new Module("domain");
        Blueprint blueprint = new Blueprint(Set.of(domain), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of());
        ArchitectureProfile profile = new ComputeMetrics(new AnalyzeSnapshot()).compute(blueprint, snapshot);

        HealthReport report = new HealthReport(
                0, Set.of(), Set.of(), DriftDirection.STABLE, 0,
                profile, List.of(), List.of(), List.of());

        QueryArchitecture qa = new QueryArchitecture((sys, msg) ->
                "WHAT: All good\nWHY IT HURTS: N/A\nNEXT ACTION: None\nEFFORT: S\nSKIP IF: Always");

        String response = qa.query(report, profile, "is this healthy?");
        assertEquals("WHAT: All good\nWHY IT HURTS: N/A\nNEXT ACTION: None\nEFFORT: S\nSKIP IF: Always",
                response);
    }

    private String capturePrompt(HealthReport report, ArchitectureProfile profile, String question) {
        StringBuilder captured = new StringBuilder();
        QueryArchitecture qa = new QueryArchitecture((sys, msg) -> {
            captured.append(msg);
            return "mocked response";
        });
        qa.query(report, profile, question);
        return captured.toString();
    }
}
