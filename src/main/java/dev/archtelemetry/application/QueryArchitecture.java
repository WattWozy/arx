package dev.archtelemetry.application;

import dev.archtelemetry.application.port.LlmClient;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Violation;
import dev.archtelemetry.domain.ViolationRecord;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class QueryArchitecture {

    private final LlmClient llmClient;

    public QueryArchitecture(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public String query(HealthReport report, ArchitectureProfile profile, String question) {
        String context = formatContext(report, profile);
        String userMessage = context + "\n\nQuestion: " + question;
        return llmClient.query(systemPrompt(), userMessage);
    }

    private static String systemPrompt() {
        return """
                You are an expert software architect. Analyze the provided architecture metrics and answer questions.
                For each distinct finding, use this exact format:

                WHAT: <concise description of the issue or observation>
                WHY IT HURTS: <impact on maintainability, testability, or reliability>
                NEXT ACTION: <specific, concrete remediation step with module names>
                EFFORT: <S, M, or L>
                SKIP IF: <condition under which this finding can be safely ignored>

                Separate multiple findings with "---". Reference specific module names from the context.
                Be actionable and specific — avoid generic advice.
                """;
    }

    private static String formatContext(HealthReport report, ArchitectureProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Architecture Analysis Context ===\n\n");

        sb.append("Drift direction: ").append(report.driftDirection()).append('\n');
        sb.append("Total violations: ").append(report.totalViolations()).append('\n');
        sb.append("New violations: ").append(report.newViolations().size()).append('\n');
        sb.append('\n');

        sb.append("Modules (sorted by risk):\n");
        List<ModuleMetrics> ranked = profile.moduleMetrics().stream()
                .sorted(Comparator.comparingDouble(ModuleMetrics::hubScore).reversed()
                        .thenComparingDouble(ModuleMetrics::instability).reversed())
                .collect(Collectors.toList());
        for (ModuleMetrics m : ranked) {
            sb.append(String.format("  %s: fanIn=%d fanOut=%d instability=%.2f wmc=%d"
                            + " pageRank=%.4f betweenness=%.4f hubScore=%.4f"
                            + " crapScore=%.2f testDebtScore=%.2f%n",
                    m.module().name(), m.fanIn(), m.fanOut(), m.instability(), m.wmc(),
                    m.pageRank(), m.betweenness(), m.hubScore(),
                    m.crapScore(), m.testDebtScore()));
        }
        sb.append('\n');

        if (!profile.violations().isEmpty()) {
            sb.append("Current violations:\n");
            profile.violations().stream()
                    .sorted(Comparator.comparing(v -> v.dependency().source().name()))
                    .forEach(v -> sb.append("  ")
                            .append(v.dependency().source().name())
                            .append(" -> ")
                            .append(v.dependency().target().name())
                            .append('\n'));
            sb.append('\n');
        }

        if (!profile.cycles().isEmpty()) {
            sb.append("Dependency cycles:\n");
            for (DependencyCycle cycle : profile.cycles()) {
                String path = cycle.modules().stream().map(mod -> mod.name())
                        .collect(Collectors.joining(" -> "));
                sb.append("  ").append(path).append('\n');
            }
            sb.append('\n');
        }

        List<ViolationRecord> chronic = report.violationRecords().stream()
                .filter(ViolationRecord::isChronic).toList();
        if (!chronic.isEmpty()) {
            sb.append("Chronic violations (3+ snapshots):\n");
            chronic.forEach(vr -> sb.append("  ")
                    .append(vr.violation().dependency().source().name())
                    .append(" -> ")
                    .append(vr.violation().dependency().target().name())
                    .append(" (").append(vr.ageInSnapshots()).append(" snapshots)\n"));
            sb.append('\n');
        }

        return sb.toString();
    }
}
