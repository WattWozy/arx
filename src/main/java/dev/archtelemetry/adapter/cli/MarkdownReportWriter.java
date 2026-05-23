package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.StaleModuleWarning;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.ViolationRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class MarkdownReportWriter {

    public static String generate(Trend trend, HealthReport report, List<Snapshot> snapshots) {
        return generate(trend, report, snapshots, List.of());
    }

    public static String generate(Trend trend, HealthReport report, List<Snapshot> snapshots,
                                  List<StaleModuleWarning> staleWarnings) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Arx Health Report\n\n");

        String trendIcon = switch (report.driftDirection()) {
            case IMPROVING -> "&#x2705;";
            case STABLE -> "&#x27A1;&#xFE0F;";
            case DEGRADING -> "&#x26A0;&#xFE0F;";
        };
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Snapshots analyzed | ").append(report.snapshotsAnalyzed()).append(" |\n");
        sb.append("| Trend | ").append(trendIcon).append(" ").append(report.driftDirection().name()).append(" |\n");
        sb.append("| Current violations | ").append(report.totalViolations()).append(" |\n\n");

        List<Trend.SnapshotEntry> entries = trend.entries();
        sb.append("## Snapshot History\n\n");
        if (!entries.isEmpty()) {
            sb.append("```\n").append(buildSparkline(entries)).append("\n```\n\n");
        }
        sb.append("| # | Commit | Timestamp | Violations |\n");
        sb.append("|---|--------|-----------|------------|\n");
        for (int i = 0; i < entries.size(); i++) {
            Trend.SnapshotEntry entry = entries.get(i);
            Snapshot snap = snapshots.get(i);
            String commitId = entry.commitId();
            sb.append("| ").append(i + 1)
              .append(" | `").append(commitId, 0, Math.min(8, commitId.length())).append("`")
              .append(" | ").append(snap.timestamp())
              .append(" | ").append(entry.violationCount())
              .append(" |\n");
        }
        sb.append("\n");

        if (report.latestProfile() != null) {
            sb.append("## Module Metrics\n\n");
            sb.append("| Module | Layer | Fan-In | Fan-Out | Instability | WMC | Hotspot | ChurnAcc | BusFactor | Flags |\n");
            sb.append("|--------|-------|--------|---------|-------------|-----|---------|----------|-----------|-------|\n");
            report.latestProfile().moduleMetrics().stream()
                    .sorted(Comparator.comparing(m -> m.module().name()))
                    .forEach(m -> sb.append("| ").append(m.module().name())
                            .append(" | ").append(m.module().layer() < 0 ? "-" : m.module().layer())
                            .append(" | ").append(m.fanIn())
                            .append(" | ").append(m.fanOut())
                            .append(String.format(Locale.US, " | %.2f", m.instability()))
                            .append(" | ").append(m.wmc())
                            .append(String.format(Locale.US, " | %.1f", m.hotspot()))
                            .append(String.format(Locale.US, " | %.2f", m.churnAcceleration()))
                            .append(String.format(Locale.US, " | %.2f", m.busFactorRisk()))
                            .append(" | ").append(mdFlag(m))
                            .append(" |\n"));
            sb.append("\n");

            sb.append("## Dependency Cycles\n\n");
            if (report.latestProfile().cycles().isEmpty()) {
                sb.append("_No cycles detected_\n\n");
            } else {
                report.latestProfile().cycles().forEach(cycle -> {
                    String path = cycle.modules().stream()
                            .map(mod -> mod.name())
                            .collect(Collectors.joining(" -> "));
                    sb.append("- :warning: ").append(path).append("\n");
                });
                sb.append("\n");
            }
        }

        sb.append("## Current Violations\n\n");
        if (entries.isEmpty() || entries.get(entries.size() - 1).violations().isEmpty()) {
            sb.append("_None_\n\n");
        } else {
            entries.get(entries.size() - 1).violations().stream()
                    .map(v -> "- `" + v.dependency().source().name() + "` -> `" + v.dependency().target().name() + "`")
                    .sorted()
                    .forEach(s -> sb.append(s).append("\n"));
            sb.append("\n");
        }

        sb.append("## New Since Previous Snapshot\n\n");
        if (report.newViolations().isEmpty()) {
            sb.append("_None_\n\n");
        } else {
            report.newViolations().stream()
                    .map(v -> "- `" + v.dependency().source().name() + "` -> `" + v.dependency().target().name() + "`")
                    .sorted()
                    .forEach(s -> sb.append(s).append("\n"));
            sb.append("\n");
        }

        sb.append("## Resolved Since Previous Snapshot\n\n");
        if (report.resolvedViolations().isEmpty()) {
            sb.append("_None_\n\n");
        } else {
            report.resolvedViolations().stream()
                    .map(v -> "- `" + v.dependency().source().name() + "` -> `" + v.dependency().target().name() + "`")
                    .sorted()
                    .forEach(s -> sb.append(s).append("\n"));
            sb.append("\n");
        }

        List<ViolationRecord> chronic = report.violationRecords().stream()
                .filter(ViolationRecord::isChronic)
                .sorted(Comparator.comparingInt(ViolationRecord::ageInSnapshots).reversed())
                .toList();
        if (!chronic.isEmpty()) {
            sb.append("## Chronic Violations (3+ snapshots)\n\n");
            chronic.forEach(vr -> sb.append("- :warning: `")
                    .append(vr.violation().dependency().source().name())
                    .append("` -> `")
                    .append(vr.violation().dependency().target().name())
                    .append("` _(").append(vr.ageInSnapshots()).append(" snapshots)_\n"));
            sb.append("\n");
        }

        if (!report.instabilityWarnings().isEmpty()) {
            sb.append("## Instability Warnings\n\n");
            report.instabilityWarnings().stream()
                    .sorted(Comparator.comparing(w -> w.module().name()))
                    .forEach(w -> sb.append("- :warning: **").append(w.module().name())
                            .append("**: ").append(w.reason()).append("\n"));
            sb.append("\n");
        }

        if (!staleWarnings.isEmpty()) {
            sb.append("## Blueprint Warnings — Stale Modules\n\n");
            staleWarnings.stream()
                    .sorted(Comparator.comparing(w -> w.module().name()))
                    .forEach(w -> sb.append("- :warning: **").append(w.module().name())
                            .append("** — no files matched this module in the latest snapshot\n"));
            sb.append("\n");
        }

        sb.append("---\n_Generated by Arx_\n");
        return sb.toString();
    }

    private static String buildSparkline(List<Trend.SnapshotEntry> entries) {
        int max = entries.stream().mapToInt(Trend.SnapshotEntry::violationCount).max().orElse(0);
        char[] bars = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
        return entries.stream()
                .map(e -> max == 0 ? "▁" : String.valueOf(bars[Math.min(7, (int) (7.0 * e.violationCount() / max))]))
                .collect(Collectors.joining());
    }

    private static String mdFlag(ModuleMetrics m) {
        boolean isHotspot = m.hotspot() > 0 && m.hotspot() > 50;
        boolean isBusFactor = m.busFactorRisk() > 5.0;
        if (isHotspot || isBusFactor) {
            return (isHotspot ? ":warning: hotspot " : "") + (isBusFactor ? ":warning: bus-factor" : "");
        }
        int layer = m.module().layer();
        boolean highInstability = m.instability() > 0.5;
        if (layer < 0) return highInstability ? ":warning: high coupling" : "";
        if (layer <= 1) return highInstability ? ":warning: high coupling" : (layer == 0 ? "stable core" : "healthy");
        return highInstability ? "expected" : "";
    }
}
