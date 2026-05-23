package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.adapter.coverage.JacocoXmlCoverageSource;
import dev.archtelemetry.adapter.coverage.LcovCoverageSource;
import dev.archtelemetry.adapter.git.GitHistorySource;
import dev.archtelemetry.adapter.http.ArxHttpServer;
import dev.archtelemetry.adapter.mcp.ArxMcpServer;
import dev.archtelemetry.adapter.git.GitSnapshotSource;
import dev.archtelemetry.adapter.git.Language;
import dev.archtelemetry.adapter.git.SnapshotConfig;
import dev.archtelemetry.adapter.java.JavaDependencyResolver;
import dev.archtelemetry.adapter.llm.AnthropicLlmClient;
import dev.archtelemetry.adapter.persistence.H2ScanResultStore;
import dev.archtelemetry.adapter.typescript.TypeScriptDependencyResolver;
import dev.archtelemetry.application.AnalyzeHistory;
import dev.archtelemetry.application.AnalyzeIncremental;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.BlueprintValidator;
import dev.archtelemetry.application.ComputeGitStats;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.application.IncrementalResult;
import dev.archtelemetry.application.InferBlueprint;
import dev.archtelemetry.application.QueryArchitecture;
import dev.archtelemetry.application.ReportHealth;
import dev.archtelemetry.application.port.CoverageSource;
import dev.archtelemetry.application.port.LocatingDependencyResolver;
import dev.archtelemetry.application.port.ResolvedData;
import dev.archtelemetry.application.port.ResolvedDataWithLocations;
import dev.archtelemetry.application.port.ScanResultStore;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.CommitEntry;
import dev.archtelemetry.domain.Hotspot;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleGitStats;
import dev.archtelemetry.domain.ScanRecord;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.StaleModuleWarning;
import dev.archtelemetry.domain.Trend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
            return;
        }
        switch (args[0]) {
            case "--version", "-v" -> printVersion();
            case "scan"            -> runScanCommand(args);
            case "watch"           -> runWatchCommand(args);
            case "check"           -> runCheckCommand(args);
            case "infer"           -> runInferCommand(args);
            case "query"           -> runQueryCommand(args);
            case "mcp-serve"       -> runMcpServe(args);
            case "serve"           -> runServeCommand(args);
            default -> {
                System.err.println("Unknown subcommand: " + args[0]);
                System.err.println();
                printUsage();
                System.exit(1);
            }
        }
    }

    // -------------------------------------------------------------------------
    // scan — full analysis with git history
    // -------------------------------------------------------------------------

    private static void runScanCommand(String[] args) {
        Path repoPath = null;
        Path blueprintPath = null;
        Path outPath = null;
        Path coveragePath = null;
        int commitCount = 20;
        String format = "console";
        String language = "java";
        String dbPath = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--repo"      -> repoPath = Path.of(args[++i]);
                case "--blueprint" -> blueprintPath = Path.of(args[++i]);
                case "--commits"   -> commitCount = Integer.parseInt(args[++i]);
                case "--format"    -> format = args[++i];
                case "--out"       -> outPath = Path.of(args[++i]);
                case "--language"  -> language = args[++i];
                case "--coverage"  -> coveragePath = Path.of(args[++i]);
                case "--db"        -> dbPath = args[++i];
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: arx scan --repo <path> --blueprint <path> [options]");
                    System.exit(1);
                }
            }
        }

        if (repoPath == null || blueprintPath == null) {
            System.err.println("scan requires --repo and --blueprint");
            System.err.println("Usage: arx scan --repo <path> --blueprint <path>");
            System.err.println("  [--commits N] [--format console|json|markdown|html|pdf|ai-feedback]");
            System.err.println("  [--out file] [--language java|typescript|auto] [--coverage file] [--db file]");
            System.exit(1);
            return;
        }

        Path scanRoot = repoPath.toAbsolutePath().normalize();
        Path gitRoot = resolveGitRoot(scanRoot);

        Blueprint blueprint = BlueprintLoader.load(blueprintPath);
        JavaDependencyResolver javaResolver = new JavaDependencyResolver(blueprint.modules());
        Language lang = parseLanguage(language);
        CoverageSource coverageSource = buildCoverageSource(coveragePath);
        ScanResultStore store = createStore(dbPath);
        String blueprintHash = computeBlueprintHash(blueprintPath);
        String blueprintText = readBlueprintText(blueprintPath);

        runNormal(blueprint, javaResolver, lang, blueprint.modules(), gitRoot, scanRoot, commitCount,
                format, outPath, List.of(), coverageSource, store, blueprintHash, blueprintText);
    }

    // -------------------------------------------------------------------------
    // watch — filesystem watcher or one-shot incremental
    // -------------------------------------------------------------------------

    private static void runWatchCommand(String[] args) {
        Path repoPath = null;
        Path blueprintPath = null;
        Path srcDir = null;
        String language = "java";
        String format = "console";
        List<Path> changedFiles = new ArrayList<>();
        boolean incrementalMode = false;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--blueprint" -> blueprintPath = Path.of(args[++i]);
                case "--repo"      -> repoPath = Path.of(args[++i]);
                case "--src"       -> srcDir = Path.of(args[++i]);
                case "--language"  -> language = args[++i];
                case "--format"    -> format = args[++i];
                case "--changed"   -> {
                    incrementalMode = true;
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        changedFiles.add(Path.of(args[++i]));
                    }
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: arx watch --blueprint <path> [--src <dir>] [--repo <path>]");
                    System.err.println("  [--language java|typescript|auto] [--format console|ai-feedback]");
                    System.err.println("  [--changed <files>...]");
                    System.exit(1);
                }
            }
        }

        if (blueprintPath == null) {
            System.err.println("watch requires --blueprint");
            System.err.println("Usage: arx watch --blueprint <path> [--src <dir>] [--repo <path>]");
            System.err.println("  [--language java|typescript|auto] [--format console|ai-feedback]");
            System.err.println("  [--changed <files>...]  (omit for continuous filesystem watcher)");
            System.exit(1);
            return;
        }

        Blueprint blueprint = BlueprintLoader.load(blueprintPath);
        Language lang = parseLanguage(language);
        Path projectRoot = repoPath != null ? repoPath : Path.of(".").toAbsolutePath().normalize();
        LocatingDependencyResolver resolver = buildResolver(lang, blueprint, projectRoot);
        String fileExt = lang == Language.TYPESCRIPT ? ".ts" : ".java";

        if (incrementalMode) {
            runIncremental(blueprint, resolver, repoPath, srcDir, changedFiles, fileExt, format);
        } else {
            runWatch(blueprint, resolver, repoPath, srcDir, fileExt, format);
        }
    }

    // -------------------------------------------------------------------------
    // check — CI gate with exit codes
    // -------------------------------------------------------------------------

    private static void runCheckCommand(String[] args) {
        Path repoPath = null;
        Path blueprintPath = null;
        Path coveragePath = null;
        int commitCount = 20;
        String language = "java";
        String dbPath = null;
        List<String> failOnConditions = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--repo"      -> repoPath = Path.of(args[++i]);
                case "--blueprint" -> blueprintPath = Path.of(args[++i]);
                case "--commits"   -> commitCount = Integer.parseInt(args[++i]);
                case "--language"  -> language = args[++i];
                case "--coverage"  -> coveragePath = Path.of(args[++i]);
                case "--db"        -> dbPath = args[++i];
                case "--fail-on"   -> failOnConditions.add(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: arx check --repo <path> --blueprint <path> [options]");
                    System.exit(1);
                }
            }
        }

        if (repoPath == null || blueprintPath == null) {
            System.err.println("check requires --repo and --blueprint");
            System.err.println("Usage: arx check --repo <path> --blueprint <path>");
            System.err.println("  [--commits N] [--language java|typescript|auto] [--coverage file] [--db file]");
            System.err.println("  [--fail-on new-violations|any-violations|new-cycles|stale-blueprint|instability-threshold=<N>]");
            System.exit(1);
            return;
        }

        if (failOnConditions.isEmpty()) {
            failOnConditions.add("any-violations");
        }

        Path scanRoot = repoPath.toAbsolutePath().normalize();
        Path gitRoot = resolveGitRoot(scanRoot);

        Blueprint blueprint = BlueprintLoader.load(blueprintPath);
        JavaDependencyResolver javaResolver = new JavaDependencyResolver(blueprint.modules());
        Language lang = parseLanguage(language);
        CoverageSource coverageSource = buildCoverageSource(coveragePath);
        ScanResultStore store = createStore(dbPath);
        String blueprintHash = computeBlueprintHash(blueprintPath);
        String blueprintText = readBlueprintText(blueprintPath);

        runNormal(blueprint, javaResolver, lang, blueprint.modules(), gitRoot, scanRoot, commitCount,
                "check", null, failOnConditions, coverageSource, store, blueprintHash, blueprintText);
    }

    // -------------------------------------------------------------------------
    // infer — blueprint generation
    // -------------------------------------------------------------------------

    private static void runInferCommand(String[] args) {
        Path repoPath = null;
        int depth = 2;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--repo"     -> repoPath = Path.of(args[++i]);
                case "--depth"    -> depth = Integer.parseInt(args[++i]);
                case "--language" -> { i++; /* java only for now */ }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: arx infer --repo <path> [--depth 2]");
                    System.exit(1);
                }
            }
        }

        if (repoPath == null) {
            System.err.println("infer requires --repo");
            System.err.println("Usage: arx infer --repo <path> [--depth 2]");
            System.exit(1);
            return;
        }

        Path scanRoot = repoPath.toAbsolutePath().normalize();
        Path gitRoot = resolveGitRoot(scanRoot);

        Set<Path> javaFiles = WorkingTreeScanner.scanJavaFiles(scanRoot);
        if (javaFiles.isEmpty()) {
            System.err.println("No .java files found under: " + scanRoot);
            System.exit(1);
            return;
        }

        Set<String> allPackages = new HashSet<>();
        List<Map.Entry<String, String>> packageDeps = new ArrayList<>();
        Pattern pkgPattern = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
        Pattern impPattern = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);

        for (Path file : javaFiles) {
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException e) {
                continue;
            }
            Matcher pm = pkgPattern.matcher(source);
            if (!pm.find()) continue;
            String pkg = pm.group(1);
            allPackages.add(pkg);

            Matcher im = impPattern.matcher(source);
            while (im.find()) {
                String imp = im.group(1);
                if (imp.startsWith("java.") || imp.startsWith("javax.")) continue;
                String importedPkg = imp.contains(".")
                        ? imp.substring(0, imp.lastIndexOf('.')) : imp;
                if (importedPkg.endsWith(".*")) importedPkg = importedPkg.substring(0, importedPkg.length() - 2);
                packageDeps.add(Map.entry(pkg, importedPkg));
            }
        }

        String blueprintText = new InferBlueprint().infer(allPackages, packageDeps, depth);
        String scope = gitRoot.relativize(scanRoot).toString().replace('\\', '/');
        if (!scope.isEmpty()) {
            System.out.print("scope " + scope + "\n");
        }
        System.out.print(blueprintText);
    }

    // -------------------------------------------------------------------------
    // query — natural language interface
    // -------------------------------------------------------------------------

    private static void runQueryCommand(String[] args) {
        Path repoPath = null;
        Path blueprintPath = null;
        int commitCount = 20;
        String question = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--repo"      -> repoPath = Path.of(args[++i]);
                case "--blueprint" -> blueprintPath = Path.of(args[++i]);
                case "--commits"   -> commitCount = Integer.parseInt(args[++i]);
                default -> {
                    if (!args[i].startsWith("--")) {
                        question = args[i];
                    } else {
                        System.err.println("Unknown argument: " + args[i]);
                        System.err.println("Usage: arx query --repo <path> --blueprint <path> \"question\"");
                        System.exit(1);
                    }
                }
            }
        }

        if (repoPath == null || blueprintPath == null || question == null) {
            System.err.println("query requires --repo, --blueprint, and a question");
            System.err.println("Usage: arx query --repo <path> --blueprint <path> \"question\"");
            System.err.println("Requires ARX_API_KEY environment variable.");
            System.exit(1);
            return;
        }

        String apiKey = System.getenv("ARX_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ARX_API_KEY environment variable is not set.");
            System.exit(1);
            return;
        }

        Path scanRoot = repoPath.toAbsolutePath().normalize();
        Path gitRoot = resolveGitRoot(scanRoot);

        Blueprint blueprint = BlueprintLoader.load(blueprintPath);
        JavaDependencyResolver javaResolver = new JavaDependencyResolver(blueprint.modules());

        GitSnapshotSource snapshotSource = new GitSnapshotSource(
                gitRoot, scanRoot, javaResolver,
                root -> new TypeScriptDependencyResolver(blueprint.modules(), root),
                Language.JAVA, new SnapshotConfig.LastN(commitCount));
        GitHistorySource historySource = new GitHistorySource(
                gitRoot, scanRoot, new SnapshotConfig.LastN(commitCount));

        AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
        AnalyzeHistory analyzeHistory = new AnalyzeHistory(analyzeSnapshot);
        ComputeMetrics computeMetrics = new ComputeMetrics(analyzeSnapshot);
        ComputeGitStats computeGitStats = new ComputeGitStats();
        ReportHealth reportHealth = new ReportHealth();

        List<Snapshot> snapshots = snapshotSource.fetchSnapshots();
        List<CommitEntry> history = historySource.fetchHistory();
        Map<Module, ModuleGitStats> gitStats = computeGitStats.compute(blueprint, history);
        Trend trend = analyzeHistory.analyze(blueprint, snapshots);
        List<ArchitectureProfile> profiles = snapshots.stream()
                .map(s -> computeMetrics.compute(blueprint, s, gitStats))
                .toList();
        HealthReport report = reportHealth.report(trend, profiles);
        ArchitectureProfile profile = report.latestProfile() != null
                ? report.latestProfile()
                : new ArchitectureProfile(Set.of(), Set.of(), Set.of());

        QueryArchitecture queryArchitecture = new QueryArchitecture(new AnthropicLlmClient(apiKey));
        String answer = queryArchitecture.query(report, profile, question);
        System.out.println(answer);
    }

    // -------------------------------------------------------------------------
    // Core: normal (scan/check) mode
    // -------------------------------------------------------------------------

    private static void runNormal(Blueprint blueprint, JavaDependencyResolver javaResolver,
                                  Language lang, Set<Module> modules,
                                  Path gitRoot, Path scanRoot, int commitCount, String format,
                                  Path outPath, List<String> failOnConditions,
                                  CoverageSource coverageSource,
                                  ScanResultStore store, String blueprintHash, String blueprintText) {
        GitSnapshotSource snapshotSource = new GitSnapshotSource(
                gitRoot, scanRoot, javaResolver,
                root -> new TypeScriptDependencyResolver(modules, root),
                lang, new SnapshotConfig.LastN(commitCount));
        GitHistorySource historySource = new GitHistorySource(
                gitRoot, scanRoot, new SnapshotConfig.LastN(commitCount));

        AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
        AnalyzeHistory analyzeHistory = new AnalyzeHistory(analyzeSnapshot);
        ComputeMetrics computeMetrics = new ComputeMetrics(analyzeSnapshot);
        ComputeGitStats computeGitStats = new ComputeGitStats();
        ReportHealth reportHealth = new ReportHealth();
        BlueprintValidator blueprintValidator = new BlueprintValidator();

        List<Snapshot> snapshots = snapshotSource.fetchSnapshots();
        List<CommitEntry> history = historySource.fetchHistory();
        Map<Module, ModuleGitStats> gitStats = computeGitStats.compute(blueprint, history);

        Trend trend = analyzeHistory.analyze(blueprint, snapshots);
        List<ArchitectureProfile> profiles = snapshots.stream()
                .map(s -> computeMetrics.compute(blueprint, s, gitStats, coverageSource))
                .toList();
        HealthReport report = reportHealth.report(trend, profiles);

        if (store != null) {
            for (int i = 0; i < snapshots.size(); i++) {
                Snapshot snap = snapshots.get(i);
                ArchitectureProfile profile = profiles.get(i);
                List<Hotspot> hotspots = profile.moduleMetrics().stream()
                        .filter(m -> m.hotspot() > 0)
                        .map(m -> new Hotspot(m.module().name(), 0, m.wmc(), m.hotspot()))
                        .toList();
                ScanRecord record = new ScanRecord(
                        scanRoot.toString(), snap.commitId(), snap.timestamp(), blueprintHash,
                        blueprintText,
                        new ArrayList<>(profile.violations()),
                        new ArrayList<>(profile.moduleMetrics()),
                        hotspots,
                        new ArrayList<>(profile.cycles()));
                try {
                    store.storeScanResult(record);
                } catch (Exception e) {
                    System.err.println("[arx] warning: failed to persist scan: " + e.getMessage());
                }
            }
        }

        List<StaleModuleWarning> staleWarnings = snapshots.isEmpty()
                ? List.of()
                : blueprintValidator.validate(blueprint, snapshots.get(snapshots.size() - 1));

        switch (format) {
            case "console"     -> HealthReportPrinter.print(trend, report, snapshots, staleWarnings);
            case "json"        -> writeOutput(JsonReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "markdown"    -> writeOutput(MarkdownReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "html"        -> writeOutput(HtmlReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "pdf"         -> writeBinaryOutput(PdfReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "ai-feedback" -> {
                Set<dev.archtelemetry.domain.Violation> violations = report.latestProfile() != null
                        ? report.latestProfile().violations()
                        : Set.of();
                writeOutput(AiFeedbackWriter.generate(violations, blueprint), outPath);
            }
            case "check" -> {
                if (report.latestProfile() != null && !report.latestProfile().violations().isEmpty()) {
                    report.latestProfile().violations().stream()
                            .map(v -> "  " + v.dependency().source().name() + " -> " + v.dependency().target().name())
                            .sorted()
                            .forEach(System.err::println);
                }
                if (!staleWarnings.isEmpty()) {
                    staleWarnings.stream()
                            .sorted(Comparator.comparing(w -> w.module().name()))
                            .map(w -> "  stale: " + w.module().name())
                            .forEach(System.err::println);
                }
            }
            default -> {
                System.err.println("Unknown format: " + format
                        + ". Valid: console, json, markdown, html, pdf, ai-feedback");
                System.exit(1);
            }
        }

        int exitCode = evaluateFailOn(failOnConditions, report, staleWarnings);
        if (exitCode != 0) System.exit(exitCode);
    }

    // -------------------------------------------------------------------------
    // Core: incremental mode
    // -------------------------------------------------------------------------

    private static void runIncremental(Blueprint blueprint, LocatingDependencyResolver resolver,
                                       Path repoPath, Path srcDir,
                                       List<Path> changedFiles, String fileExt, String format) {
        Path effectiveSrcDir = resolveSrcDir(repoPath, srcDir);

        Snapshot baseline;
        if (repoPath != null) {
            GitSnapshotSource snapshotSource = new GitSnapshotSource(
                    repoPath, resolver, new SnapshotConfig.LastN(1));
            List<Snapshot> snapshots = snapshotSource.fetchSnapshots();
            baseline = snapshots.isEmpty() ? emptySnapshot() : snapshots.get(0);
        } else {
            if (effectiveSrcDir == null || !Files.isDirectory(effectiveSrcDir)) {
                System.err.println("watch --changed without --repo requires --src <source-dir>");
                System.exit(1);
                return;
            }
            Set<Path> allFiles = WorkingTreeScanner.scanFiles(effectiveSrcDir, fileExt);
            ResolvedData data = resolver.resolve(allFiles);
            baseline = new Snapshot("baseline", Instant.now(), data.dependencies(), data.moduleWmc());
        }

        if (changedFiles.isEmpty()) {
            readChangedFilesFromStdin(changedFiles);
        }
        if (changedFiles.isEmpty()) {
            System.err.println("No changed files. Use --changed <file>... or pipe paths to stdin.");
            System.exit(1);
            return;
        }

        List<Path> existing = changedFiles.stream().filter(Files::exists).toList();
        if (existing.isEmpty()) {
            System.err.println("All changed files are deleted. No incremental analysis possible.");
            System.exit(0);
            return;
        }

        AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
        AnalyzeIncremental analyzeIncremental = new AnalyzeIncremental(resolver, analyzeSnapshot);

        ResolvedDataWithLocations located = resolver.resolveWithLocations(Set.copyOf(existing));
        IncrementalResult result = analyzeIncremental.analyze(Set.copyOf(existing), blueprint, baseline);

        switch (format) {
            case "ai-feedback" -> {
                System.out.print(AiFeedbackWriter.generate(
                        result.newViolations(), located.locatedDependencies(), blueprint));
            }
            default -> {
                System.out.println("Changed files : " + existing.size());
                System.out.println("New violations: " + result.newViolations().size());
                System.out.println("All violations: " + result.allViolations().size());
                if (!result.newViolations().isEmpty()) {
                    System.out.println();
                    result.newViolations().forEach(v ->
                            System.out.println("  [VIOLATION] "
                                    + v.dependency().source().name() + " -> "
                                    + v.dependency().target().name()));
                }
            }
        }

        if (!result.newViolations().isEmpty()) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // Core: continuous filesystem watcher
    // -------------------------------------------------------------------------

    private static void runWatch(Blueprint blueprint, LocatingDependencyResolver resolver,
                                 Path repoPath, Path srcDir, String fileExt, String format) {
        Path effectiveSrcDir = resolveSrcDir(repoPath, srcDir);
        if (effectiveSrcDir == null || !Files.isDirectory(effectiveSrcDir)) {
            System.err.println("watch requires --src <source-dir> (or --repo with a src/main/java subdirectory)");
            System.exit(1);
            return;
        }

        boolean aiFeedback = "ai-feedback".equals(format);
        WatchMode watchMode = new WatchMode(effectiveSrcDir, blueprint, resolver, fileExt, aiFeedback);
        try {
            watchMode.run();
        } catch (IOException e) {
            System.err.println("Watch error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // serve — HTTP server
    // -------------------------------------------------------------------------

    private static void runServeCommand(String[] args) {
        int port = 8080;
        String dbPath = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--db"   -> dbPath = args[++i];
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: arx serve [--port 8080] [--db <path>]");
                    System.exit(1);
                }
            }
        }

        ScanResultStore store = createStore(dbPath);
        if (store == null) {
            System.err.println("[arx] Cannot start HTTP server: database unavailable");
            System.exit(1);
            return;
        }
        try {
            new ArxHttpServer(store, port).start();
        } catch (java.io.IOException e) {
            System.err.println("Failed to start HTTP server: " + e.getMessage());
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // mcp-serve
    // -------------------------------------------------------------------------

    private static void runMcpServe(String[] args) {
        String dbPath = null;
        for (int i = 1; i < args.length; i++) {
            if ("--db".equals(args[i])) {
                dbPath = args[++i];
            } else {
                System.err.println("Unknown argument: " + args[i]);
                System.err.println("Usage: arx mcp-serve [--db <path>]");
                System.exit(1);
                return;
            }
        }
        ScanResultStore store = createStore(dbPath);
        new ArxMcpServer(BlueprintLoader::load, WorkingTreeScanner::scanJavaFiles, store).run();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ScanResultStore createStore(String dbPath) {
        try {
            String url;
            if (dbPath != null) {
                url = "jdbc:h2:file:" + dbPath;
            } else {
                Path arxDir = Path.of(System.getProperty("user.home"), ".arx");
                Files.createDirectories(arxDir);
                url = "jdbc:h2:file:" + arxDir.resolve("arx");
            }
            return new H2ScanResultStore(url);
        } catch (Exception e) {
            System.err.println("[arx] warning: DB unavailable: " + e.getMessage());
            return null;
        }
    }

    private static String computeBlueprintHash(Path blueprintPath) {
        try {
            byte[] content = Files.readAllBytes(blueprintPath);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String readBlueprintText(Path blueprintPath) {
        try {
            return Files.readString(blueprintPath);
        } catch (Exception e) {
            return "";
        }
    }

    private static Language parseLanguage(String language) {
        return switch (language) {
            case "typescript" -> Language.TYPESCRIPT;
            case "auto"       -> Language.AUTO;
            default           -> Language.JAVA;
        };
    }

    private static LocatingDependencyResolver buildResolver(Language lang, Blueprint blueprint, Path projectRoot) {
        return switch (lang) {
            case TYPESCRIPT -> new TypeScriptDependencyResolver(blueprint.modules(), projectRoot);
            default         -> new JavaDependencyResolver(blueprint.modules());
        };
    }

    private static CoverageSource buildCoverageSource(Path coveragePath) {
        if (coveragePath == null) return null;
        String name = coveragePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".xml")) return new JacocoXmlCoverageSource(coveragePath);
        if (name.equals("lcov.info") || name.endsWith(".lcov")) return new LcovCoverageSource(coveragePath);
        return new JacocoXmlCoverageSource(coveragePath);
    }

    private static Path resolveSrcDir(Path repoPath, Path srcDir) {
        if (srcDir != null) return srcDir;
        if (repoPath != null) {
            Path candidate = repoPath.resolve("src/main/java");
            return Files.isDirectory(candidate) ? candidate : repoPath;
        }
        return null;
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot("empty", Instant.now(), Set.of(), Map.of());
    }

    private static void readChangedFilesFromStdin(List<Path> changedFiles) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) changedFiles.add(Path.of(line));
            }
        }
    }

    private static int evaluateFailOn(List<String> conditions, HealthReport report,
                                      List<StaleModuleWarning> staleWarnings) {
        int exitCode = 0;
        for (String condition : conditions) {
            boolean triggered = switch (condition) {
                case "new-violations"  -> !report.newViolations().isEmpty();
                case "any-violations"  -> report.totalViolations() > 0;
                case "new-cycles"      -> report.latestProfile() != null
                        && !report.latestProfile().cycles().isEmpty();
                case "stale-blueprint" -> !staleWarnings.isEmpty();
                default -> {
                    if (condition.startsWith("instability-threshold=")) {
                        try {
                            double threshold = Double.parseDouble(
                                    condition.substring("instability-threshold=".length()));
                            yield report.latestProfile() != null
                                    && report.latestProfile().moduleMetrics().stream()
                                       .anyMatch(m -> m.instability() > threshold);
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid threshold in: " + condition);
                            yield false;
                        }
                    }
                    System.err.println("Unknown --fail-on condition: " + condition
                            + ". Valid: new-violations, any-violations, new-cycles, "
                            + "instability-threshold=<N>, stale-blueprint");
                    yield false;
                }
            };
            if (triggered) {
                System.err.println("FAIL: --fail-on " + condition + " triggered");
                exitCode = 1;
            }
        }
        return exitCode;
    }

    private static void writeBinaryOutput(byte[] content, Path outPath) {
        if (outPath == null) {
            try {
                System.out.write(content);
                System.out.flush();
            } catch (IOException e) {
                System.err.println("Failed to write PDF: " + e.getMessage());
                System.exit(1);
            }
        } else {
            try {
                Files.write(outPath, content);
                System.err.println("Report written to: " + outPath);
            } catch (IOException e) {
                System.err.println("Failed to write report: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    private static void writeOutput(String content, Path outPath) {
        if (outPath == null) {
            System.out.print(content);
        } else {
            try {
                Files.writeString(outPath, content);
                System.err.println("Report written to: " + outPath);
            } catch (IOException e) {
                System.err.println("Failed to write report: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    private static Path resolveGitRoot(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return path.toAbsolutePath().normalize();
    }

    private static void printVersion() {
        String version = Main.class.getPackage().getImplementationVersion();
        System.out.println("arx " + (version != null ? version : "dev"));
    }

    private static void printUsage() {
        System.err.println("""
                Usage: arx <subcommand> [options]

                Subcommands:
                  scan    Full analysis with git history — text, json, markdown, or html report
                  watch   Real-time feedback loop — filesystem watcher or one-shot incremental
                  check   CI gate — minimal output, exit 1 on violations
                  infer   Generate a blueprint draft from source code
                  query     Ask a natural language question about your architecture
                  mcp-serve Start an MCP server on stdio (for Claude Code / AI tools)
                  serve     Start an HTTP server exposing scan data as JSON APIs

                Quick start:
                  arx infer --repo .
                  arx scan  --repo . --blueprint arch.blu

                Options:
                  --version, -v   Print version and exit

                scan options:
                  --repo <path>         Git repository (required)
                  --blueprint <path>    Blueprint file (required)
                  --commits <n>         Commits to analyze (default: 20)
                  --format <fmt>        console | json | markdown | html | pdf | ai-feedback
                  --out <file>          Write output to file (default: stdout)
                  --language <lang>     java | typescript | auto (default: java)
                  --coverage <file>     JaCoCo XML or lcov.info

                watch options:
                  --blueprint <path>    Blueprint file (required)
                  --src <dir>           Source directory (default: <repo>/src/main/java)
                  --repo <path>         Git repository (for baseline)
                  --language <lang>     java | typescript | auto (default: java)
                  --format <fmt>        console | ai-feedback (default: console)
                  --changed <files>...  One-shot incremental mode (omit for filesystem watcher)

                check options:
                  --repo <path>         Git repository (required)
                  --blueprint <path>    Blueprint file (required)
                  --commits <n>         Commits to analyze (default: 20)
                  --language <lang>     java | typescript | auto (default: java)
                  --coverage <file>     JaCoCo XML or lcov.info
                  --fail-on <cond>      new-violations | any-violations (default) | new-cycles |
                                        stale-blueprint | instability-threshold=<N>

                infer options:
                  --repo <path>         Git repository (required)
                  --depth <n>           Package grouping depth (default: 2)

                query options:
                  --repo <path>         Git repository (required)
                  --blueprint <path>    Blueprint file (required)
                  --commits <n>         Commits to analyze (default: 20)
                  "question"            Natural language question (positional)

                serve options:
                  --port <n>            HTTP port (default: 8080)
                  --db <path>           H2 database path (default: ~/.arx/arx)

                Environment:
                  ARX_API_KEY   Anthropic API key (required for query)
                  ARX_MODEL     Model for query (default: claude-haiku-4-5-20251001)
                """);
    }
}
