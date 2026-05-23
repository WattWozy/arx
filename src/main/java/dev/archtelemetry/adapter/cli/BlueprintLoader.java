package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BlueprintLoader {

    public static Blueprint load(Path path) {
        try {
            List<String> lines = Files.readAllLines(path);
            Map<String, List<String>> modulePatterns = new LinkedHashMap<>();
            Map<String, Integer> moduleLayers = new LinkedHashMap<>();
            List<String[]> allowRules = new ArrayList<>();
            Optional<String> scope = Optional.empty();

            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("scope ")) {
                    scope = Optional.of(line.substring(6).strip());
                } else if (line.startsWith("module ")) {
                    String rest = line.substring(7).strip();
                    int space = rest.indexOf(' ');
                    if (space < 0) continue;
                    String name = rest.substring(0, space).strip();
                    String remaining = rest.substring(space + 1).strip();

                    String[] tokens = remaining.split("\\s+");
                    int layer = -1;
                    StringBuilder patternBuilder = new StringBuilder();
                    for (String token : tokens) {
                        if (token.startsWith("layer=")) {
                            try { layer = Integer.parseInt(token.substring(6)); } catch (NumberFormatException ignored) {}
                        } else {
                            if (patternBuilder.length() > 0) patternBuilder.append(" ");
                            patternBuilder.append(token);
                        }
                    }
                    modulePatterns.computeIfAbsent(name, k -> new ArrayList<>()).add(patternBuilder.toString());
                    moduleLayers.put(name, layer);
                } else if (line.startsWith("allow ")) {
                    String rest = line.substring(6).strip();
                    String[] parts = rest.split("\\s*->\\s*", 2);
                    if (parts.length == 2) {
                        allowRules.add(new String[]{parts[0].strip(), parts[1].strip()});
                    }
                }
            }

            Map<String, Module> modules = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : modulePatterns.entrySet()) {
                int layer = moduleLayers.getOrDefault(e.getKey(), -1);
                modules.put(e.getKey(), new Module(e.getKey(), e.getValue(), layer));
            }

            Set<Dependency> allowed = new HashSet<>();
            for (String[] rule : allowRules) {
                Module source = modules.get(rule[0]);
                Module target = modules.get(rule[1]);
                if (source != null && target != null) {
                    allowed.add(new Dependency(source, target));
                }
            }

            return new Blueprint(new HashSet<>(modules.values()), allowed, scope);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
