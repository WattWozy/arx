package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.application.InstabilityWarning;
import dev.archtelemetry.domain.ArchitectureCommunity;
import dev.archtelemetry.domain.DriftDirection;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.RefactoringSuggestion;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.StaleModuleWarning;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.Violation;
import dev.archtelemetry.domain.ViolationRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class HealthReportPrinter {

    public static void print(Trend trend, HealthReport report, List<Snapshot> snapshots) {
        print(trend, report, snapshots, List.of());
    }

    public static void print(Trend trend, HealthReport report, List<Snapshot> snapshots,
                             List<StaleModuleWarning> staleWarnings) {
        if (!staleWarnings.isEmpty()) {
            System.out.println("--- Blueprint Warnings: Stale Modules ---");
            staleWarnings.stream()
                    .sorted(Comparator.comparing(w -> w.module().name()))
                    .forEach(w -> System.out.println("  ⚠ " + w.module().name()
                            + ": no files matched this module in the latest snapshot"));
            System.out.println();
        }
        System.out.println("Arx Health Report");
        System.out.println("===========================");
        System.out.println();
        System.out.printf("Snapshots analyzed : %d%n", snapshots.size());
        System.out.printf("Trend              : %s%n", formatDirection(report.driftDirection()));
        System.out.printf("Current violations : %d%n", report.totalViolations());
        System.out.println();

        List<Trend.SnapshotEntry> entries = trend.entries();
        System.out.println("--- Snapshot History (oldest -> newest) ---");
        for (int i = 0; i < entries.size(); i++) {
            Trend.SnapshotEntry entry = entries.get(i);
            Snapshot snapshot = snapshots.get(i);
            int count = entry.violationCount();
            System.out.printf("  %.8s  %s  %d %s%n",
                    entry.commitId(),
                    snapshot.timestamp(),
                    count,
                    count == 1 ? "violation" : "violations");
        }
        System.out.println();

        if (report.latestProfile() != null) {
            System.out.println("--- Module Metrics (latest snapshot) ---");
            System.out.printf("%-20s %6s %7s %11s %5s %11s %8s %9s %9s %9s  %s%n",
                    "Module", "Fan-In", "Fan-Out", "Instability", "WMC",
                    "Abstractness", "Distance", "Hotspot", "ChurnAcc", "BusFactor", "Flags");
            report.latestProfile().moduleMetrics().stream()
                    .sorted(Comparator.comparing(m -> m.module().name()))
                    .forEach(m -> System.out.printf("%-20s %6d %7d %11.2f %5d %11.2f %8.2f %9.1f %9.2f %9.2f  %s%n",
                            m.module().name(), m.fanIn(), m.fanOut(), m.instability(),
                            m.wmc(), m.abstractness(), m.distanceFromMainSequence(),
                            m.hotspot(), m.churnAcceleration(), m.busFactorRisk(),
                            moduleFlag(m)));
            System.out.println();

            boolean hasCoverage = report.latestProfile().moduleMetrics().stream()
                    .anyMatch(m -> m.crapScore() > 0);
            boolean hasGraphMetrics = report.latestProfile().moduleMetrics().stream()
                    .anyMatch(m -> m.pageRank() > 0);

            if (hasGraphMetrics) {
                System.out.println("--- Hub Scores (PageRank × Betweenness × WMC) ---");
                System.out.printf("%-20s %10s %13s %10s%n", "Module", "PageRank", "Betweenness", "HubScore");
                report.latestProfile().moduleMetrics().stream()
                        .sorted(Comparator.comparingDouble(ModuleMetrics::hubScore).reversed())
                        .forEach(m -> System.out.printf("%-20s %10.4f %13.4f %10.4f%n",
                                m.module().name(), m.pageRank(), m.betweenness(), m.hubScore()));
                System.out.println();
            }

            if (hasCoverage) {
                System.out.println("--- Test Debt (CRAP Score) ---");
                System.out.printf("%-20s %12s %13s%n", "Module", "CRAP Score", "Test Debt");
                report.latestProfile().moduleMetrics().stream()
                        .sorted(Comparator.comparingDouble(ModuleMetrics::testDebtScore).reversed())
                        .filter(m -> m.crapScore() > 0)
                        .forEach(m -> System.out.printf("%-20s %12.2f %13.2f%n",
                                m.module().name(), m.crapScore(), m.testDebtScore()));
                System.out.println();
            }

            if (!report.latestProfile().refactoringSuggestions().isEmpty()) {
                System.out.println("--- Refactoring Suggestions ---");
                report.latestProfile().refactoringSuggestions().forEach(s -> {
                    String tag = s.type() == RefactoringSuggestion.Type.SPLIT ? "SPLIT" : "MERGE";
                    System.out.printf("  [%s] %s: %s%n", tag, s.module().name(), s.reason());
                });
                System.out.println();
            }

            Set<ArchitectureCommunity> communities = report.latestProfile().communities();
            if (!communities.isEmpty()) {
                System.out.println("--- Architecture Communities ---");
                communities.stream()
                        .sorted(Comparator.comparing(c -> -c.modules().size()))
                        .forEach(c -> {
                            String members = c.modules().stream()
                                    .map(m -> m.name())
                                    .sorted()
                                    .collect(Collectors.joining(", "));
                            boolean crossLayer = c.modules().stream()
                                    .mapToInt(m -> m.layer())
                                    .distinct().count() > 1;
                            String note = crossLayer ? "  ⚠ cross-layer coupling" : "";
                            System.out.printf("  [%d] %s%s%n", c.modules().size(), members, note);
                        });
                System.out.println();
            }

            System.out.println("--- Dependency Cycles ---");
            if (report.latestProfile().cycles().isEmpty()) {
                System.out.println("  (none)");
            } else {
                report.latestProfile().cycles().forEach(cycle -> {
                    String path = cycle.modules().stream()
                            .map(mod -> mod.name())
                            .collect(Collectors.joining(" -> "));
                    System.out.println("  ⚠ " + path);
                });
            }
            System.out.println();
        }

        System.out.printf("--- Current Violations (%d) ---%n", report.totalViolations());
        if (entries.isEmpty()) {
            System.out.println("  (none)");
        } else {
            Set<Violation> current = entries.get(entries.size() - 1).violations();
            if (current.isEmpty()) {
                System.out.println("  (none)");
            } else {
                current.stream()
                        .map(v -> "  " + v.dependency().source().name() + " -> " + v.dependency().target().name())
                        .sorted()
                        .forEach(System.out::println);
            }
        }
        System.out.println();

        System.out.println("--- New Since Previous Snapshot ---");
        if (report.newViolations().isEmpty()) {
            System.out.println("  (none)");
        } else {
            report.newViolations().stream()
                    .map(v -> "  " + v.dependency().source().name() + " -> " + v.dependency().target().name())
                    .sorted()
                    .forEach(System.out::println);
        }
        System.out.println();

        System.out.println("--- Resolved Since Previous Snapshot ---");
        if (report.resolvedViolations().isEmpty()) {
            System.out.println("  (none)");
        } else {
            report.resolvedViolations().stream()
                    .map(v -> "  " + v.dependency().source().name() + " -> " + v.dependency().target().name())
                    .sorted()
                    .forEach(System.out::println);
        }

        List<ViolationRecord> chronic = report.violationRecords().stream()
                .filter(ViolationRecord::isChronic)
                .sorted(Comparator.comparingInt(ViolationRecord::ageInSnapshots).reversed())
                .toList();
        if (!chronic.isEmpty()) {
            System.out.println();
            System.out.println("--- Chronic Violations (3+ snapshots) ---");
            chronic.forEach(vr -> System.out.printf("  ⚠ %s -> %s  (%d snapshots)%n",
                    vr.violation().dependency().source().name(),
                    vr.violation().dependency().target().name(),
                    vr.ageInSnapshots()));
        }

        if (!report.instabilityWarnings().isEmpty()) {
            System.out.println();
            System.out.println("--- Instability Warnings ---");
            report.instabilityWarnings().stream()
                    .sorted(Comparator.comparing(w -> w.module().name()))
                    .forEach(w -> System.out.println("  ⚠ " + w.module().name() + ": " + w.reason()));
        }
    }

    private static String moduleFlag(ModuleMetrics m) {
        int layer = m.module().layer();
        boolean highInstability = m.instability() > 0.5;
        boolean isHotspot = m.hotspot() > 0 && m.hotspot() > 50;
        boolean isBusFactor = m.busFactorRisk() > 5.0;

        StringBuilder flags = new StringBuilder();
        if (isHotspot) flags.append("⚠ hotspot ");
        if (isBusFactor) flags.append("⚠ bus-factor ");
        if (flags.length() > 0) return flags.toString().trim();

        if (layer < 0) {
            return highInstability ? "⚠ high coupling" : "";
        }
        if (layer <= 1) {
            return highInstability ? "⚠ high coupling" : (layer == 0 ? "✓ stable core" : "✓ healthy");
        }
        return highInstability ? "✓ expected" : "";
    }

    private static String formatDirection(DriftDirection direction) {
        return switch (direction) {
            case IMPROVING -> "IMPROVING (improving)";
            case STABLE -> "STABLE";
            case DEGRADING -> "DEGRADING (worsening)";
        };
    }
}
