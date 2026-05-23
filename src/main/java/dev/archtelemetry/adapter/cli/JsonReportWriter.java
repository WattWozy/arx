package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.StaleModuleWarning;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.ViolationRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class JsonReportWriter {

    public static String generate(Trend trend, HealthReport report, List<Snapshot> snapshots) {
        return generate(trend, report, snapshots, List.of());
    }

    public static String generate(Trend trend, HealthReport report, List<Snapshot> snapshots,
                                  List<StaleModuleWarning> staleWarnings) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // summary
        sb.append("  \"summary\": {\n");
        sb.append("    \"snapshotsAnalyzed\": ").append(report.snapshotsAnalyzed()).append(",\n");
        sb.append("    \"trend\": ").append(str(report.driftDirection().name())).append(",\n");
        sb.append("    \"totalViolations\": ").append(report.totalViolations()).append("\n");
        sb.append("  },\n");

        // history
        List<Trend.SnapshotEntry> entries = trend.entries();
        sb.append("  \"history\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Trend.SnapshotEntry entry = entries.get(i);
            Snapshot snap = snapshots.get(i);
            sb.append("    { \"commitId\": ").append(str(entry.commitId()))
              .append(", \"timestamp\": ").append(str(snap.timestamp().toString()))
              .append(", \"violationCount\": ").append(entry.violationCount())
              .append(" }");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // moduleMetrics
        sb.append("  \"moduleMetrics\": [\n");
        if (report.latestProfile() != null) {
            List<ModuleMetrics> metrics = report.latestProfile().moduleMetrics().stream()
                    .sorted(Comparator.comparing(m -> m.module().name()))
                    .toList();
            for (int i = 0; i < metrics.size(); i++) {
                ModuleMetrics m = metrics.get(i);
                sb.append("    {\n");
                sb.append("      \"name\": ").append(str(m.module().name())).append(",\n");
                sb.append("      \"layer\": ").append(m.module().layer()).append(",\n");
                sb.append("      \"fanIn\": ").append(m.fanIn()).append(",\n");
                sb.append("      \"fanOut\": ").append(m.fanOut()).append(",\n");
                sb.append("      \"instability\": ").append(fmt(m.instability())).append(",\n");
                sb.append("      \"abstractness\": ").append(fmt(m.abstractness())).append(",\n");
                sb.append("      \"distanceFromMainSequence\": ").append(fmt(m.distanceFromMainSequence())).append(",\n");
                sb.append("      \"wmc\": ").append(m.wmc()).append(",\n");
                sb.append("      \"hotspot\": ").append(fmt(m.hotspot())).append(",\n");
                sb.append("      \"churnAcceleration\": ").append(fmt(m.churnAcceleration())).append(",\n");
                sb.append("      \"busFactorRisk\": ").append(fmt(m.busFactorRisk())).append("\n");
                sb.append("    }");
                if (i < metrics.size() - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ],\n");

        // cycles
        sb.append("  \"cycles\": [\n");
        if (report.latestProfile() != null) {
            List<DependencyCycle> cycles = report.latestProfile().cycles().stream().toList();
            for (int i = 0; i < cycles.size(); i++) {
                String mods = cycles.get(i).modules().stream()
                        .map(mod -> str(mod.name()))
                        .collect(Collectors.joining(", "));
                sb.append("    { \"modules\": [").append(mods).append("] }");
                if (i < cycles.size() - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ],\n");

        // allDependencies (from latest snapshot, for graph rendering)
        sb.append("  \"allDependencies\": [\n");
        if (!snapshots.isEmpty()) {
            List<String> depLines = snapshots.get(snapshots.size() - 1).dependencies().stream()
                    .map(d -> "    { \"source\": " + str(d.source().name()) + ", \"target\": " + str(d.target().name()) + " }")
                    .sorted()
                    .toList();
            sb.append(String.join(",\n", depLines));
            if (!depLines.isEmpty()) sb.append("\n");
        }
        sb.append("  ],\n");

        // violations
        sb.append("  \"violations\": {\n");

        sb.append("    \"current\": [\n");
        if (!entries.isEmpty()) {
            List<String> current = entries.get(entries.size() - 1).violations().stream()
                    .map(v -> "      { \"source\": " + str(v.dependency().source().name())
                            + ", \"target\": " + str(v.dependency().target().name()) + " }")
                    .sorted().toList();
            sb.append(String.join(",\n", current));
            if (!current.isEmpty()) sb.append("\n");
        }
        sb.append("    ],\n");

        sb.append("    \"new\": [\n");
        List<String> newV = report.newViolations().stream()
                .map(v -> "      { \"source\": " + str(v.dependency().source().name())
                        + ", \"target\": " + str(v.dependency().target().name()) + " }")
                .sorted().toList();
        sb.append(String.join(",\n", newV));
        if (!newV.isEmpty()) sb.append("\n");
        sb.append("    ],\n");

        sb.append("    \"resolved\": [\n");
        List<String> resolved = report.resolvedViolations().stream()
                .map(v -> "      { \"source\": " + str(v.dependency().source().name())
                        + ", \"target\": " + str(v.dependency().target().name()) + " }")
                .sorted().toList();
        sb.append(String.join(",\n", resolved));
        if (!resolved.isEmpty()) sb.append("\n");
        sb.append("    ],\n");

        sb.append("    \"chronic\": [\n");
        List<ViolationRecord> chronic = report.violationRecords().stream()
                .filter(ViolationRecord::isChronic)
                .sorted(Comparator.comparingInt(ViolationRecord::ageInSnapshots).reversed())
                .toList();
        List<String> chronicLines = chronic.stream()
                .map(vr -> "      { \"source\": " + str(vr.violation().dependency().source().name())
                        + ", \"target\": " + str(vr.violation().dependency().target().name())
                        + ", \"ageInSnapshots\": " + vr.ageInSnapshots() + " }")
                .toList();
        sb.append(String.join(",\n", chronicLines));
        if (!chronicLines.isEmpty()) sb.append("\n");
        sb.append("    ]\n");

        sb.append("  },\n");

        // instabilityWarnings
        sb.append("  \"instabilityWarnings\": [\n");
        List<String> warnings = report.instabilityWarnings().stream()
                .sorted(Comparator.comparing(w -> w.module().name()))
                .map(w -> "    { \"module\": " + str(w.module().name()) + ", \"reason\": " + str(w.reason()) + " }")
                .toList();
        sb.append(String.join(",\n", warnings));
        if (!warnings.isEmpty()) sb.append("\n");
        sb.append("  ],\n");

        sb.append("  \"staleModules\": [\n");
        List<String> staleLines = staleWarnings.stream()
                .sorted(Comparator.comparing(w -> w.module().name()))
                .map(w -> "    " + str(w.module().name()))
                .toList();
        sb.append(String.join(",\n", staleLines));
        if (!staleLines.isEmpty()) sb.append("\n");
        sb.append("  ]\n");

        sb.append("}\n");
        return sb.toString();
    }

    static String str(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0.0";
        return String.format(Locale.US, "%.4f", d);
    }
}
