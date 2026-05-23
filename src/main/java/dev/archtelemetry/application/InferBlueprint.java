package dev.archtelemetry.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class InferBlueprint {

    private static final int DEFAULT_FREQUENCY_THRESHOLD = 1;

    public String infer(Set<String> allPackages, List<Map.Entry<String, String>> packageDeps, int prefixDepth) {
        return infer(allPackages, packageDeps, prefixDepth, DEFAULT_FREQUENCY_THRESHOLD);
    }

    public String infer(Set<String> allPackages, List<Map.Entry<String, String>> packageDeps,
                        int prefixDepth, int frequencyThreshold) {
        if (allPackages.isEmpty()) return "# (no packages found)\n";

        String[] commonPfx = findCommonPrefix(allPackages);

        // Package -> module key
        Map<String, String> pkgToModule = new LinkedHashMap<>();
        Set<String> moduleKeys = new TreeSet<>();
        for (String pkg : allPackages) {
            String key = moduleKey(pkg, commonPfx, prefixDepth);
            pkgToModule.put(pkg, key);
            moduleKeys.add(key);
        }

        // Count module-level dep frequencies
        Map<String, Integer> depFrequencies = new TreeMap<>();
        for (Map.Entry<String, String> dep : packageDeps) {
            String srcModule = pkgToModule.get(dep.getKey());
            String tgtModule = pkgToModule.getOrDefault(dep.getValue(), moduleKey(dep.getValue(), commonPfx, prefixDepth));
            if (srcModule == null || srcModule.equals(tgtModule)) continue;
            String key = srcModule + " -> " + tgtModule;
            depFrequencies.merge(key, 1, Integer::sum);
        }

        // Collect allowed deps above threshold
        Set<String[]> allowedDeps = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : depFrequencies.entrySet()) {
            if (entry.getValue() >= frequencyThreshold) {
                String[] parts = entry.getKey().split(" -> ", 2);
                allowedDeps.add(parts);
            }
        }

        String pfxStr = commonPfx.length > 0 ? String.join(".", commonPfx) : "";
        return renderBlueprint(pfxStr, moduleKeys, allowedDeps);
    }

    private String[] findCommonPrefix(Set<String> packages) {
        List<String[]> segmented = new ArrayList<>();
        for (String pkg : packages) segmented.add(pkg.split("\\."));
        if (segmented.isEmpty()) return new String[0];

        String[] ref = segmented.get(0);
        int len = ref.length;
        for (String[] segs : segmented) {
            int i = 0;
            while (i < len && i < segs.length && ref[i].equals(segs[i])) i++;
            len = i;
        }
        return Arrays.copyOf(ref, len);
    }

    private String moduleKey(String pkg, String[] commonPfx, int depth) {
        String[] segs = pkg.split("\\.");
        int start = commonPfx.length;
        int end = Math.min(segs.length, start + depth);
        if (start >= segs.length) return pkg;
        return String.join(".", Arrays.copyOfRange(segs, 0, end));
    }

    private String moduleName(String key, String pfxStr) {
        String suffix = pfxStr.isEmpty() ? key : key.startsWith(pfxStr + ".")
                ? key.substring(pfxStr.length() + 1) : key;
        return suffix.replace('.', '-');
    }

    private String renderBlueprint(String pfxStr, Set<String> moduleKeys, Set<String[]> allowedDeps) {
        Map<String, String> keyToName = new TreeMap<>();
        for (String key : moduleKeys) {
            keyToName.put(key, moduleName(key, pfxStr));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Inferred blueprint\n");
        for (Map.Entry<String, String> entry : keyToName.entrySet()) {
            sb.append(String.format("module %-30s %s.**%n", entry.getValue(), entry.getKey()));
        }
        if (!allowedDeps.isEmpty()) {
            sb.append('\n');
            for (String[] dep : allowedDeps) {
                String srcName = keyToName.getOrDefault(dep[0], dep[0]);
                String tgtName = keyToName.getOrDefault(dep[1], dep[1]);
                sb.append(String.format("allow %s -> %s%n", srcName, tgtName));
            }
        }
        return sb.toString();
    }
}
