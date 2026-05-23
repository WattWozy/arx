package dev.archtelemetry.application;

import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.CommitEntry;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleGitStats;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ComputeGitStats {

    public Map<Module, ModuleGitStats> compute(Blueprint blueprint, List<CommitEntry> history) {
        if (history.isEmpty()) return Map.of();

        Instant reference = history.stream()
                .map(CommitEntry::timestamp)
                .max(Comparator.naturalOrder())
                .orElse(Instant.now());

        Instant cutoff30d = reference.minus(30, ChronoUnit.DAYS);
        Instant cutoff90d = reference.minus(90, ChronoUnit.DAYS);

        Map<Module, Integer> commitCounts = new HashMap<>();
        Map<Module, Set<String>> authorEmailsMap = new HashMap<>();
        Map<Module, Integer> commitsLast30dMap = new HashMap<>();
        Map<Module, Integer> commitsWindow31to90Map = new HashMap<>();

        for (CommitEntry entry : history) {
            Set<Module> touched = resolveModules(blueprint, entry.changedPaths());
            for (Module module : touched) {
                commitCounts.merge(module, 1, Integer::sum);
                authorEmailsMap.computeIfAbsent(module, k -> new HashSet<>()).add(entry.authorEmail());

                if (entry.timestamp().isAfter(cutoff30d)) {
                    commitsLast30dMap.merge(module, 1, Integer::sum);
                } else if (entry.timestamp().isAfter(cutoff90d)) {
                    commitsWindow31to90Map.merge(module, 1, Integer::sum);
                }
            }
        }

        Map<Module, ModuleGitStats> result = new HashMap<>();
        for (Module module : blueprint.modules()) {
            int cc = commitCounts.getOrDefault(module, 0);
            Set<String> emails = authorEmailsMap.getOrDefault(module, Set.of());
            int last30 = commitsLast30dMap.getOrDefault(module, 0);
            int window = commitsWindow31to90Map.getOrDefault(module, 0);
            // 31-90d window spans ~2 periods of 30d
            double avgPer30dWindow = window / 2.0;
            result.put(module, new ModuleGitStats(module, cc, emails.size(), emails, last30, avgPer30dWindow));
        }
        return Collections.unmodifiableMap(result);
    }

    private Set<Module> resolveModules(Blueprint blueprint, Set<String> changedPaths) {
        Set<Module> modules = new HashSet<>();
        for (String path : changedPaths) {
            String packageName = pathToPackage(path);
            if (packageName == null) continue;
            for (Module module : blueprint.modules()) {
                if (module.packagePatterns().stream().anyMatch(p -> packageMatchesPattern(packageName, p))) {
                    modules.add(module);
                    break;
                }
            }
        }
        return modules;
    }

    private String pathToPackage(String path) {
        int idx = path.indexOf("java/");
        if (idx < 0) idx = path.indexOf("java\\");
        if (idx < 0) return null;
        String relative = path.substring(idx + 5);
        if (!relative.endsWith(".java")) return null;
        return relative.substring(0, relative.length() - 5).replace('/', '.').replace('\\', '.');
    }

    private boolean packageMatchesPattern(String packageName, String pattern) {
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
        }
        return packageName.equals(pattern);
    }
}
