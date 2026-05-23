package dev.archtelemetry.adapter.typescript;

import dev.archtelemetry.application.port.LocatedDependency;
import dev.archtelemetry.application.port.LocatingDependencyResolver;
import dev.archtelemetry.application.port.ResolvedData;
import dev.archtelemetry.application.port.ResolvedDataWithLocations;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TypeScriptDependencyResolver implements LocatingDependencyResolver {

    // from 'path' or from "path" — covers single-line and multi-line imports/exports
    private static final Pattern FROM_CLAUSE = Pattern.compile(
            "\\bfrom\\s+['\"]([^'\"]+)['\"]");

    // import 'path' — bare side-effect import
    private static final Pattern BARE_IMPORT = Pattern.compile(
            "^\\s*import\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    // require('path')
    private static final Pattern REQUIRE = Pattern.compile(
            "\\brequire\\(['\"]([^'\"]+)['\"]\\)");

    // Abstract types: interfaces and abstract classes
    private static final Pattern TS_ABSTRACT_TYPE_DECL = Pattern.compile(
            "^[^/\n]*\\b(?:interface|abstract\\s+class)\\s+\\w+",
            Pattern.MULTILINE);
    // All type declarations: class, interface, type alias, enum
    private static final Pattern TS_TYPE_DECL = Pattern.compile(
            "^[^/\n]*\\b(?:class|interface|type|enum)\\s+\\w+",
            Pattern.MULTILINE);

    // Function/method count proxy
    private static final Pattern FUNCTION_DECL = Pattern.compile(
            // named function declarations
            "(?:^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+\\w)"
            // arrow functions with block body
            + "|(?:=>\\s*\\{)"
            // class methods: optional modifiers then identifier followed by ( and body {
            + "|(?:^\\s+(?:(?:public|private|protected|static|async|abstract|override|readonly|get|set)\\s+)*"
            + "(?!if\\b|for\\b|while\\b|switch\\b|return\\b|new\\b|throw\\b|catch\\b|else\\b)"
            + "[a-zA-Z_$][\\w$]*\\s*(?:<[^>]*>)?\\s*\\([^)]*\\)\\s*(?::\\s*[^;{]+)?\\s*\\{)",
            Pattern.MULTILINE);

    private final Set<Module> modules;
    private final Path sourceRoot;

    public TypeScriptDependencyResolver(Set<Module> modules, Path sourceRoot) {
        this.modules = Set.copyOf(modules);
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
    }

    @Override
    public ResolvedData resolve(Set<Path> sourceFiles) {
        return resolveWithLocations(sourceFiles).data();
    }

    @Override
    public ResolvedDataWithLocations resolveWithLocations(Set<Path> sourceFiles) {
        Set<Dependency> dependencies = new HashSet<>();
        Map<Module, Integer> moduleWmc = new HashMap<>();
        Map<Module, int[]> typeCounts = new HashMap<>(); // [totalTypes, abstractTypes]
        List<LocatedDependency> located = new ArrayList<>();

        for (Path file : sourceFiles) {
            if (!isTypeScriptFile(file)) continue;

            String source;
            try { source = Files.readString(file); }
            catch (IOException e) { throw new UncheckedIOException(e); }

            Path relPath = relativize(file);
            Optional<Module> sourceModule = resolveFileToModule(relPath);
            if (sourceModule.isEmpty()) continue;

            moduleWmc.merge(sourceModule.get(), countFunctions(source), Integer::sum);

            int[] counts = typeCounts.computeIfAbsent(sourceModule.get(), k -> new int[]{0, 0});
            counts[0] += countTsTypes(source);
            counts[1] += countTsAbstractTypes(source);

            for (Map.Entry<String, Integer> entry : extractImportsWithLines(source)) {
                String importPath = entry.getKey();
                int lineNum = entry.getValue();
                resolveImportToModule(importPath, relPath).ifPresent(target -> {
                    if (!target.equals(sourceModule.get())) {
                        Dependency dep = new Dependency(sourceModule.get(), target);
                        dependencies.add(dep);
                        located.add(new LocatedDependency(dep, file, lineNum, importPath));
                    }
                });
            }
        }

        Map<Module, Double> moduleAbstractness = new HashMap<>();
        typeCounts.forEach((m, c) ->
                moduleAbstractness.put(m, c[0] == 0 ? 0.0 : (double) c[1] / c[0]));

        ResolvedData data = new ResolvedData(Set.copyOf(dependencies), Map.copyOf(moduleWmc),
                Map.copyOf(moduleAbstractness));
        return new ResolvedDataWithLocations(data, List.copyOf(located));
    }

    private Path relativize(Path file) {
        try {
            return sourceRoot.relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return file.toAbsolutePath().normalize();
        }
    }

    private Optional<Module> resolveFileToModule(Path relPath) {
        String pathStr = slash(relPath.toString());
        return modules.stream()
                .filter(m -> m.packagePatterns().stream()
                        .anyMatch(p -> isTsPattern(p) && pathMatches(pathStr, p)))
                .findFirst();
    }

    private Optional<Module> resolveImportToModule(String importPath, Path fileRelPath) {
        String resolved;
        if (importPath.startsWith(".")) {
            Path dir = fileRelPath.getParent();
            if (dir == null) dir = Path.of("");
            resolved = slash(dir.resolve(importPath).normalize().toString());
        } else {
            resolved = importPath;
        }
        return modules.stream()
                .filter(m -> m.packagePatterns().stream()
                        .anyMatch(p -> isTsPattern(p) && pathMatches(resolved, p)))
                .findFirst();
    }

    private boolean isTsPattern(String pattern) {
        return pattern.contains("/");
    }

    private boolean pathMatches(String filePath, String pattern) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return filePath.equals(prefix) || filePath.startsWith(prefix + "/");
        }
        return filePath.equals(pattern);
    }

    private boolean isTypeScriptFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".ts") || name.endsWith(".tsx");
    }

    private int countFunctions(String source) {
        Matcher m = FUNCTION_DECL.matcher(source);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private int countTsTypes(String source) {
        Matcher m = TS_TYPE_DECL.matcher(source);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private int countTsAbstractTypes(String source) {
        Matcher m = TS_ABSTRACT_TYPE_DECL.matcher(source);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private List<Map.Entry<String, Integer>> extractImportsWithLines(String source) {
        List<Map.Entry<String, Integer>> result = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fm = FROM_CLAUSE.matcher(line);
            if (fm.find()) {
                result.add(Map.entry(fm.group(1), i + 1));
                continue;
            }
            Matcher bm = BARE_IMPORT.matcher(line);
            if (bm.find()) {
                result.add(Map.entry(bm.group(1), i + 1));
                continue;
            }
            Matcher rm = REQUIRE.matcher(line);
            if (rm.find()) {
                result.add(Map.entry(rm.group(1), i + 1));
            }
        }
        return result;
    }

    private static String slash(String path) {
        return path.replace('\\', '/');
    }
}
