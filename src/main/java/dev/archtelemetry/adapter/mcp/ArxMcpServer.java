package dev.archtelemetry.adapter.mcp;

import dev.archtelemetry.adapter.coverage.JacocoXmlCoverageSource;
import dev.archtelemetry.adapter.coverage.LcovCoverageSource;
import dev.archtelemetry.adapter.git.GitHistorySource;
import dev.archtelemetry.adapter.git.GitSnapshotSource;
import dev.archtelemetry.adapter.git.Language;
import dev.archtelemetry.adapter.git.SnapshotConfig;
import dev.archtelemetry.adapter.java.JavaDependencyResolver;
import dev.archtelemetry.adapter.llm.AnthropicLlmClient;
import dev.archtelemetry.adapter.typescript.TypeScriptDependencyResolver;
import dev.archtelemetry.application.AnalyzeHistory;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.ComputeGitStats;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.application.GenerateRecommendations;
import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.application.InferBlueprint;
import dev.archtelemetry.application.InstabilityWarning;
import dev.archtelemetry.application.QueryArchitecture;
import dev.archtelemetry.application.ReportHealth;
import dev.archtelemetry.application.port.BlueprintSource;
import dev.archtelemetry.application.port.CoverageSource;
import dev.archtelemetry.application.port.JavaFileScanner;
import dev.archtelemetry.application.port.ScanResultStore;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.CommitEntry;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.DependencyEdge;
import dev.archtelemetry.domain.DependencyGraph;
import dev.archtelemetry.domain.Hotspot;
import dev.archtelemetry.domain.HotspotSnapshot;
import dev.archtelemetry.domain.MetricSnapshot;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleGitStats;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Recommendation;
import dev.archtelemetry.domain.ScanRecord;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.Violation;
import dev.archtelemetry.domain.ViolationRecord;
import dev.archtelemetry.domain.ViolationTrend;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ArxMcpServer {

    @FunctionalInterface
    interface ToolExecutor {
        String execute(String name, String argumentsJson);
    }

    private final PrintStream stdout;
    private final PrintStream stderr;
    private final ToolExecutor executor;
    private final BlueprintSource blueprintSource;
    private final JavaFileScanner javaFileScanner;
    private final ScanResultStore store;

    public ArxMcpServer(BlueprintSource blueprintSource, JavaFileScanner javaFileScanner) {
        this(System.out, System.err, null, blueprintSource, javaFileScanner, null);
    }

    public ArxMcpServer(BlueprintSource blueprintSource, JavaFileScanner javaFileScanner, ScanResultStore store) {
        this(System.out, System.err, null, blueprintSource, javaFileScanner, store);
    }

    ArxMcpServer(PrintStream stdout, PrintStream stderr, ToolExecutor executor) {
        this(stdout, stderr, executor, null, null, null);
    }

    ArxMcpServer(PrintStream stdout, PrintStream stderr, ToolExecutor executor,
                 BlueprintSource blueprintSource, JavaFileScanner javaFileScanner) {
        this(stdout, stderr, executor, blueprintSource, javaFileScanner, null);
    }

    ArxMcpServer(PrintStream stdout, PrintStream stderr, ToolExecutor executor,
                 BlueprintSource blueprintSource, JavaFileScanner javaFileScanner,
                 ScanResultStore store) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.blueprintSource = blueprintSource;
        this.javaFileScanner = javaFileScanner;
        this.store = store;
        this.executor = executor != null ? executor : this::defaultExecute;
    }

    public void run() {
        stderr.println("[arx mcp] server starting on stdio");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    String response = handleMessage(line);
                    if (response != null) {
                        // MCP stdio transport requires one JSON-RPC message per line
                        stdout.println(response.replace('\n', ' ').replace('\r', ' '));
                        stdout.flush();
                    }
                } catch (Exception e) {
                    stderr.println("[arx mcp] error: " + e.getMessage());
                    stdout.println(errorResponse("null", -32603, "Internal error: " + e.getMessage()));
                    stdout.flush();
                }
            }
        } catch (Exception e) {
            stderr.println("[arx mcp] fatal: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Message dispatch
    // -------------------------------------------------------------------------

    String handleMessage(String json) {
        String id = McpJson.getRawId(json);
        String method = McpJson.getString(json, "method");

        if (method == null) {
            return errorResponse(id, -32600, "Invalid Request: missing method");
        }
        if (method.startsWith("notifications/")) {
            return null;
        }

        return switch (method) {
            case "initialize"  -> handleInitialize(id);
            case "tools/list"  -> handleToolsList(id);
            case "tools/call"  -> handleToolsCall(id, json);
            default            -> errorResponse(id, -32601, "Method not found: " + method);
        };
    }

    // -------------------------------------------------------------------------
    // Protocol handlers
    // -------------------------------------------------------------------------

    private String handleInitialize(String id) {
        return successResponse(id,
            "{\"protocolVersion\":\"2024-11-05\","
            + "\"capabilities\":{\"tools\":{}},"
            + "\"serverInfo\":{\"name\":\"arx\",\"version\":\"1.0.0\"}}");
    }

    private String handleToolsList(String id) {
        StringBuilder sb = new StringBuilder("{\"tools\":[");
        String[] defs = {
            toolDef("check_violations",
                "Check architecture violations for specific files or the entire repo against a blueprint",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"blueprint\":{\"type\":\"string\",\"description\":\"Path to blueprint (.blu) file\"},"
                + "\"files\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                + "\"description\":\"Optional list of source files to filter violations by\"}"
                + "},"
                + "\"required\":[\"repo\",\"blueprint\"]"
                + "}"),
            toolDef("get_metrics",
                "Get architecture health metrics for all modules: instability, abstractness, fan-in/out, hub score, CRAP score",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"blueprint\":{\"type\":\"string\",\"description\":\"Path to blueprint (.blu) file\"},"
                + "\"coverage\":{\"type\":\"string\",\"description\":\"Optional path to JaCoCo XML or lcov.info\"}"
                + "},"
                + "\"required\":[\"repo\",\"blueprint\"]"
                + "}"),
            toolDef("infer_blueprint",
                "Infer a blueprint from the repo's package structure",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"depth\":{\"type\":\"integer\",\"description\":\"Package grouping depth (default: 2)\",\"default\":2}"
                + "},"
                + "\"required\":[\"repo\"]"
                + "}"),
            toolDef("scan_report",
                "Full architecture health scan: violations, metrics, cycles, chronics, hotspots",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"blueprint\":{\"type\":\"string\",\"description\":\"Path to blueprint (.blu) file\"},"
                + "\"commits\":{\"type\":\"integer\",\"description\":\"Number of commits to analyze (default: 20)\",\"default\":20},"
                + "\"coverage\":{\"type\":\"string\",\"description\":\"Optional path to JaCoCo XML or lcov.info\"}"
                + "},"
                + "\"required\":[\"repo\",\"blueprint\"]"
                + "}"),
            toolDef("query_architecture",
                "Ask a natural language question about the architecture",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"blueprint\":{\"type\":\"string\",\"description\":\"Path to blueprint (.blu) file\"},"
                + "\"question\":{\"type\":\"string\",\"description\":\"Natural language question about the architecture\"}"
                + "},"
                + "\"required\":[\"repo\",\"blueprint\",\"question\"]"
                + "}"),
            toolDef("get_violation_trend",
                "Get violation count trend over recent commits for a module",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"blueprint\":{\"type\":\"string\",\"description\":\"Path to blueprint (.blu) file\"},"
                + "\"module\":{\"type\":\"string\",\"description\":\"Optional module name to filter by\"},"
                + "\"last_n_commits\":{\"type\":\"integer\",\"description\":\"Number of recent commits (default: 10)\",\"default\":10}"
                + "},"
                + "\"required\":[\"repo\",\"blueprint\"]"
                + "}"),
            toolDef("get_metric_history",
                "Get metric trends over time for a module (instability, abstractness, hub score)",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"module\":{\"type\":\"string\",\"description\":\"Module name\"},"
                + "\"last_n_commits\":{\"type\":\"integer\",\"description\":\"Number of recent commits (default: 10)\",\"default\":10}"
                + "},"
                + "\"required\":[\"repo\",\"module\"]"
                + "}"),
            toolDef("get_hotspot_history",
                "Get hotspot score history for a specific file or module",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"file\":{\"type\":\"string\",\"description\":\"File path or module name\"},"
                + "\"last_n_commits\":{\"type\":\"integer\",\"description\":\"Number of recent commits (default: 10)\",\"default\":10}"
                + "},"
                + "\"required\":[\"repo\",\"file\"]"
                + "}"),
            toolDef("is_scanned",
                "Check if a commit has already been scanned with the current blueprint",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"commit\":{\"type\":\"string\",\"description\":\"Commit hash\"},"
                + "\"blueprint\":{\"type\":\"string\",\"description\":\"Path to blueprint (.blu) file\"}"
                + "},"
                + "\"required\":[\"repo\",\"commit\",\"blueprint\"]"
                + "}"),
            toolDef("get_recommendations",
                "Generate architectural recommendations based on metrics, dependency graph, and blueprint",
                "{"
                + "\"type\": \"object\","
                + "\"properties\":{"
                + "\"repo\":{\"type\":\"string\",\"description\":\"Path to git repository\"},"
                + "\"blueprint\":{\"type\":\"string\",\"description\":\"Path to blueprint (.blu) file\"},"
                + "\"commits\":{\"type\":\"integer\",\"description\":\"Number of commits to analyze (default: 20)\",\"default\":20}"
                + "},"
                + "\"required\":[\"repo\",\"blueprint\"]"
                + "}")
        };
        for (int i = 0; i < defs.length; i++) {
            sb.append(defs[i].trim());
            if (i < defs.length - 1) sb.append(",");
        }
        sb.append("]}");
        return successResponse(id, sb.toString());
    }

    private String handleToolsCall(String id, String json) {
        String paramsJson = McpJson.getObject(json, "params");
        if (paramsJson == null) {
            return errorResponse(id, -32602, "Invalid params: missing params object");
        }
        String name = McpJson.getString(paramsJson, "name");
        if (name == null) {
            return errorResponse(id, -32602, "Invalid params: missing tool name");
        }
        String argumentsJson = McpJson.getObject(paramsJson, "arguments");
        if (argumentsJson == null) argumentsJson = "{}";

        try {
            String result = executor.execute(name, argumentsJson);
            return toolResult(id, result, false);
        } catch (IllegalArgumentException e) {
            return toolResult(id, "Error: " + e.getMessage(), true);
        } catch (Exception e) {
            stderr.println("[arx mcp] tool error [" + name + "]: " + e.getMessage());
            return toolResult(id, "Error executing " + name + ": " + e.getMessage(), true);
        }
    }

    // -------------------------------------------------------------------------
    // Tool executors
    // -------------------------------------------------------------------------

    private String defaultExecute(String name, String argumentsJson) {
        return switch (name) {
            case "check_violations"    -> runCheckViolations(argumentsJson);
            case "get_metrics"         -> runGetMetrics(argumentsJson);
            case "infer_blueprint"     -> runInferBlueprint(argumentsJson);
            case "scan_report"         -> runScanReport(argumentsJson);
            case "query_architecture"  -> runQueryArchitecture(argumentsJson);
            case "get_violation_trend" -> runGetViolationTrend(argumentsJson);
            case "get_metric_history"  -> runGetMetricHistory(argumentsJson);
            case "get_hotspot_history" -> runGetHotspotHistory(argumentsJson);
            case "is_scanned"          -> runIsScanned(argumentsJson);
            case "get_recommendations" -> runGetRecommendations(argumentsJson);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private String runCheckViolations(String argsJson) {
        String repoStr      = requireString(argsJson, "repo");
        String blueprintStr = requireString(argsJson, "blueprint");
        List<String> files  = McpJson.getStringArray(argsJson, "files");

        ScanResult scan = buildFullReport(repoStr, blueprintStr, 20, null);
        Set<Violation> violations = scan.report.latestProfile() != null
                ? scan.report.latestProfile().violations()
                : Set.of();

        if (!files.isEmpty()) {
            violations = violations.stream()
                    .filter(v -> fileMatchesModule(files, v.dependency().source()))
                    .collect(Collectors.toSet());
        }

        List<String> vLines = violations.stream()
                .sorted(Comparator.comparing(v -> v.dependency().source().name()))
                .map(v -> "    {\n"
                        + "      \"sourceModule\": " + McpJson.escape(v.dependency().source().name()) + ",\n"
                        + "      \"targetModule\": " + McpJson.escape(v.dependency().target().name()) + ",\n"
                        + "      \"sourceFile\": null,\n"
                        + "      \"line\": null,\n"
                        + "      \"suggestedFix\": " + McpJson.escape(suggestFix(v)) + "\n"
                        + "    }")
                .toList();

        StringBuilder sb = new StringBuilder("{\n  \"violations\": [\n");
        sb.append(String.join(",\n", vLines));
        if (!vLines.isEmpty()) sb.append("\n");
        sb.append("  ],\n  \"totalViolations\": ").append(violations.size()).append("\n}");
        return sb.toString();
    }

    private String runGetMetrics(String argsJson) {
        String repoStr      = requireString(argsJson, "repo");
        String blueprintStr = requireString(argsJson, "blueprint");
        String coverageStr  = McpJson.getString(argsJson, "coverage");

        ScanResult scan = buildFullReport(repoStr, blueprintStr, 20, coverageStr);
        List<ModuleMetrics> metrics = scan.report.latestProfile() != null
                ? scan.report.latestProfile().moduleMetrics().stream()
                    .sorted(Comparator.comparing(m -> m.module().name()))
                    .toList()
                : List.of();

        List<String> mLines = metrics.stream().map(m ->
                "    {\n"
                + "      \"name\": " + McpJson.escape(m.module().name()) + ",\n"
                + "      \"layer\": " + m.module().layer() + ",\n"
                + "      \"fanIn\": " + m.fanIn() + ",\n"
                + "      \"fanOut\": " + m.fanOut() + ",\n"
                + "      \"instability\": " + fmt(m.instability()) + ",\n"
                + "      \"abstractness\": " + fmt(m.abstractness()) + ",\n"
                + "      \"distanceFromMainSequence\": " + fmt(m.distanceFromMainSequence()) + ",\n"
                + "      \"wmc\": " + m.wmc() + ",\n"
                + "      \"hotspot\": " + fmt(m.hotspot()) + ",\n"
                + "      \"churnAcceleration\": " + fmt(m.churnAcceleration()) + ",\n"
                + "      \"busFactorRisk\": " + fmt(m.busFactorRisk()) + ",\n"
                + "      \"crapScore\": " + fmt(m.crapScore()) + ",\n"
                + "      \"testDebtScore\": " + fmt(m.testDebtScore()) + ",\n"
                + "      \"pageRank\": " + fmt(m.pageRank()) + ",\n"
                + "      \"betweenness\": " + fmt(m.betweenness()) + ",\n"
                + "      \"hubScore\": " + fmt(m.hubScore()) + "\n"
                + "    }"
        ).toList();

        StringBuilder sb = new StringBuilder("{\n  \"modules\": [\n");
        sb.append(String.join(",\n", mLines));
        if (!mLines.isEmpty()) sb.append("\n");
        sb.append("  ]\n}");
        return sb.toString();
    }

    private String runInferBlueprint(String argsJson) {
        String repoStr = requireString(argsJson, "repo");
        int depth = McpJson.getInt(argsJson, "depth", 2);

        Path repoPath = Path.of(repoStr);
        Set<Path> javaFiles = javaFileScanner.scan(repoPath);
        if (javaFiles.isEmpty()) {
            throw new IllegalArgumentException("No .java files found under: " + repoStr);
        }

        Set<String> allPackages = new HashSet<>();
        List<Map.Entry<String, String>> packageDeps = new ArrayList<>();
        Pattern pkgPat = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
        Pattern impPat = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);

        for (Path file : javaFiles) {
            String source;
            try { source = Files.readString(file); }
            catch (Exception e) { continue; }
            Matcher pm = pkgPat.matcher(source);
            if (!pm.find()) continue;
            String pkg = pm.group(1);
            allPackages.add(pkg);
            Matcher im = impPat.matcher(source);
            while (im.find()) {
                String imp = im.group(1);
                if (imp.startsWith("java.") || imp.startsWith("javax.")) continue;
                String importedPkg = imp.contains(".")
                        ? imp.substring(0, imp.lastIndexOf('.')) : imp;
                if (importedPkg.endsWith(".*"))
                    importedPkg = importedPkg.substring(0, importedPkg.length() - 2);
                packageDeps.add(Map.entry(pkg, importedPkg));
            }
        }

        String blueprintText = new InferBlueprint().infer(allPackages, packageDeps, depth);
        return "{\n  \"blueprint\": " + McpJson.escape(blueprintText) + "\n}";
    }

    private String runScanReport(String argsJson) {
        String repoStr      = requireString(argsJson, "repo");
        String blueprintStr = requireString(argsJson, "blueprint");
        int commits         = McpJson.getInt(argsJson, "commits", 20);
        String coverageStr  = McpJson.getString(argsJson, "coverage");

        ScanResult scan = buildFullReport(repoStr, blueprintStr, commits, coverageStr);
        HealthReport report = scan.report;
        Trend trend = scan.trend;
        List<Snapshot> snapshots = scan.snapshots;
        List<Trend.SnapshotEntry> entries = trend.entries();

        StringBuilder sb = new StringBuilder("{\n");

        sb.append("  \"summary\": {\n");
        sb.append("    \"snapshotsAnalyzed\": ").append(report.snapshotsAnalyzed()).append(",\n");
        sb.append("    \"trend\": ").append(McpJson.escape(report.driftDirection().name())).append(",\n");
        sb.append("    \"totalViolations\": ").append(report.totalViolations()).append("\n");
        sb.append("  },\n");

        sb.append("  \"history\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Trend.SnapshotEntry entry = entries.get(i);
            Snapshot snap = snapshots.get(i);
            sb.append("    { \"commitId\": ").append(McpJson.escape(entry.commitId()))
              .append(", \"timestamp\": ").append(McpJson.escape(snap.timestamp().toString()))
              .append(", \"violationCount\": ").append(entry.violationCount())
              .append(" }");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"moduleMetrics\": [\n");
        if (report.latestProfile() != null) {
            List<ModuleMetrics> metrics = report.latestProfile().moduleMetrics().stream()
                    .sorted(Comparator.comparing(m -> m.module().name()))
                    .toList();
            for (int i = 0; i < metrics.size(); i++) {
                ModuleMetrics m = metrics.get(i);
                sb.append("    {\n");
                sb.append("      \"name\": ").append(McpJson.escape(m.module().name())).append(",\n");
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

        sb.append("  \"cycles\": [\n");
        if (report.latestProfile() != null) {
            List<DependencyCycle> cycles = report.latestProfile().cycles().stream().toList();
            for (int i = 0; i < cycles.size(); i++) {
                String mods = cycles.get(i).modules().stream()
                        .map(mod -> McpJson.escape(mod.name()))
                        .collect(Collectors.joining(", "));
                sb.append("    { \"modules\": [").append(mods).append("] }");
                if (i < cycles.size() - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ],\n");

        sb.append("  \"allDependencies\": [\n");
        if (!snapshots.isEmpty()) {
            List<String> depLines = snapshots.get(snapshots.size() - 1).dependencies().stream()
                    .map(d -> "    { \"source\": " + McpJson.escape(d.source().name())
                            + ", \"target\": " + McpJson.escape(d.target().name()) + " }")
                    .sorted()
                    .toList();
            sb.append(String.join(",\n", depLines));
            if (!depLines.isEmpty()) sb.append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"violations\": {\n");
        sb.append("    \"current\": [\n");
        if (!entries.isEmpty()) {
            List<String> current = entries.get(entries.size() - 1).violations().stream()
                    .map(v -> "      { \"source\": " + McpJson.escape(v.dependency().source().name())
                            + ", \"target\": " + McpJson.escape(v.dependency().target().name()) + " }")
                    .sorted().toList();
            sb.append(String.join(",\n", current));
            if (!current.isEmpty()) sb.append("\n");
        }
        sb.append("    ],\n");
        sb.append("    \"new\": [\n");
        List<String> newV = report.newViolations().stream()
                .map(v -> "      { \"source\": " + McpJson.escape(v.dependency().source().name())
                        + ", \"target\": " + McpJson.escape(v.dependency().target().name()) + " }")
                .sorted().toList();
        sb.append(String.join(",\n", newV));
        if (!newV.isEmpty()) sb.append("\n");
        sb.append("    ],\n");
        sb.append("    \"resolved\": [\n");
        List<String> resolved = report.resolvedViolations().stream()
                .map(v -> "      { \"source\": " + McpJson.escape(v.dependency().source().name())
                        + ", \"target\": " + McpJson.escape(v.dependency().target().name()) + " }")
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
                .map(vr -> "      { \"source\": " + McpJson.escape(vr.violation().dependency().source().name())
                        + ", \"target\": " + McpJson.escape(vr.violation().dependency().target().name())
                        + ", \"ageInSnapshots\": " + vr.ageInSnapshots() + " }")
                .toList();
        sb.append(String.join(",\n", chronicLines));
        if (!chronicLines.isEmpty()) sb.append("\n");
        sb.append("    ]\n");
        sb.append("  },\n");

        sb.append("  \"instabilityWarnings\": [\n");
        List<String> warnings = report.instabilityWarnings().stream()
                .sorted(Comparator.comparing(w -> w.module().name()))
                .map(w -> "    { \"module\": " + McpJson.escape(w.module().name())
                        + ", \"reason\": " + McpJson.escape(w.reason()) + " }")
                .toList();
        sb.append(String.join(",\n", warnings));
        if (!warnings.isEmpty()) sb.append("\n");
        sb.append("  ],\n");

        sb.append("  \"staleModules\": [],\n");
        sb.append(buildRecommendationsJson(scan));
        sb.append("}\n");
        return sb.toString();
    }

    private String runQueryArchitecture(String argsJson) {
        String repoStr      = requireString(argsJson, "repo");
        String blueprintStr = requireString(argsJson, "blueprint");
        String question     = requireString(argsJson, "question");

        String apiKey = System.getenv("ARX_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ARX_API_KEY environment variable not set");
        }

        ScanResult scan = buildFullReport(repoStr, blueprintStr, 20, null);
        ArchitectureProfile profile = scan.report.latestProfile() != null
                ? scan.report.latestProfile()
                : new ArchitectureProfile(Set.of(), Set.of(), Set.of());

        String answer = new QueryArchitecture(new AnthropicLlmClient(apiKey))
                .query(scan.report, profile, question);
        return "{\n  \"answer\": " + McpJson.escape(answer) + "\n}";
    }

    private String runGetRecommendations(String argsJson) {
        String repoStr      = requireString(argsJson, "repo");
        String blueprintStr = requireString(argsJson, "blueprint");
        int commits         = McpJson.getInt(argsJson, "commits", 20);

        ScanResult scan = buildFullReport(repoStr, blueprintStr, commits, null);
        StringBuilder sb = new StringBuilder("{\n");
        sb.append(buildRecommendationsJson(scan));
        sb.append("}\n");
        return sb.toString();
    }

    private String buildRecommendationsJson(ScanResult scan) {
        StringBuilder sb = new StringBuilder();
        sb.append("  \"recommendations\": [\n");
        if (scan.report.latestProfile() != null && !scan.snapshots.isEmpty()) {
            Set<dev.archtelemetry.domain.Dependency> deps =
                    scan.snapshots.get(scan.snapshots.size() - 1).dependencies();
            Set<Module> modules = new java.util.HashSet<>(scan.blueprint.modules());
            DependencyGraph graph = DependencyGraph.from(deps, modules);
            Map<Module, ModuleMetrics> metricsMap = scan.report.latestProfile().moduleMetrics().stream()
                    .collect(Collectors.toMap(ModuleMetrics::module, m -> m));
            List<Recommendation> recs = new GenerateRecommendations()
                    .generate(graph, metricsMap, scan.blueprint);
            for (int i = 0; i < recs.size(); i++) {
                Recommendation r = recs.get(i);
                sb.append("    {\n");
                sb.append("      \"module\": ").append(McpJson.escape(r.target().name())).append(",\n");
                sb.append("      \"severity\": ").append(McpJson.escape(r.severity().name())).append(",\n");
                sb.append("      \"code\": ").append(McpJson.escape(r.code())).append(",\n");
                sb.append("      \"summary\": ").append(McpJson.escape(r.summary())).append(",\n");
                sb.append("      \"rationale\": ").append(McpJson.escape(r.rationale())).append(",\n");
                List<String> evidence = r.evidence().stream()
                        .map(e -> McpJson.escape(e.source().name() + " -> " + e.target().name()
                                + " [" + e.kind().name() + "]"))
                        .toList();
                sb.append("      \"evidence\": [").append(String.join(", ", evidence)).append("]\n");
                sb.append("    }");
                if (i < recs.size() - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ]\n");
        return sb.toString();
    }

    private String runGetViolationTrend(String argsJson) {
        String repoStr      = requireString(argsJson, "repo");
        String blueprintStr = requireString(argsJson, "blueprint");
        String moduleFilter = McpJson.getString(argsJson, "module");
        int lastN           = McpJson.getInt(argsJson, "last_n_commits", 10);

        if (store != null) {
            List<ViolationTrend> trends = store.getViolationTrend(repoStr, moduleFilter, lastN);
            if (!trends.isEmpty()) {
                return formatViolationTrendFromDb(trends);
            }
        }

        ScanResult scan = buildFullReport(repoStr, blueprintStr, lastN, null);
        persistScanResults(repoStr, blueprintStr, scan);
        return formatViolationTrendFromScan(scan, moduleFilter);
    }

    private String runGetMetricHistory(String argsJson) {
        String repoStr = requireString(argsJson, "repo");
        String module  = requireString(argsJson, "module");
        int lastN      = McpJson.getInt(argsJson, "last_n_commits", 10);

        if (store == null) return "{\"history\": []}";
        List<MetricSnapshot> history = store.getMetricHistory(repoStr, module, lastN);
        List<String> lines = history.stream().map(ms ->
                "    {\n"
                + "      \"commit\": " + McpJson.escape(ms.commitHash()) + ",\n"
                + "      \"timestamp\": " + McpJson.escape(ms.timestamp().toString()) + ",\n"
                + "      \"instability\": " + fmt(ms.instability()) + ",\n"
                + "      \"abstractness\": " + fmt(ms.abstractness()) + ",\n"
                + "      \"hubScore\": " + fmt(ms.hubScore()) + ",\n"
                + "      \"fanIn\": " + ms.fanIn() + ",\n"
                + "      \"fanOut\": " + ms.fanOut() + ",\n"
                + "      \"wmc\": " + ms.wmc() + ",\n"
                + "      \"crapScore\": " + fmt(ms.crapScore()) + ",\n"
                + "      \"pageRank\": " + fmt(ms.pageRank()) + ",\n"
                + "      \"betweenness\": " + fmt(ms.betweenness()) + "\n"
                + "    }").toList();
        StringBuilder sb = new StringBuilder("{\n  \"history\": [\n");
        sb.append(String.join(",\n", lines));
        if (!lines.isEmpty()) sb.append("\n");
        sb.append("  ]\n}");
        return sb.toString();
    }

    private String runGetHotspotHistory(String argsJson) {
        String repoStr  = requireString(argsJson, "repo");
        String filePath = requireString(argsJson, "file");
        int lastN       = McpJson.getInt(argsJson, "last_n_commits", 10);

        if (store == null) return "{\"history\": []}";
        List<HotspotSnapshot> history = store.getHotspotHistory(repoStr, filePath, lastN);
        List<String> lines = history.stream().map(hs ->
                "    {\n"
                + "      \"commit\": " + McpJson.escape(hs.commitHash()) + ",\n"
                + "      \"timestamp\": " + McpJson.escape(hs.timestamp().toString()) + ",\n"
                + "      \"churn\": " + hs.churn() + ",\n"
                + "      \"complexity\": " + hs.complexity() + ",\n"
                + "      \"score\": " + fmt(hs.score()) + "\n"
                + "    }").toList();
        StringBuilder sb = new StringBuilder("{\n  \"history\": [\n");
        sb.append(String.join(",\n", lines));
        if (!lines.isEmpty()) sb.append("\n");
        sb.append("  ]\n}");
        return sb.toString();
    }

    private String runIsScanned(String argsJson) {
        String repoStr      = requireString(argsJson, "repo");
        String commit       = requireString(argsJson, "commit");
        String blueprintStr = requireString(argsJson, "blueprint");

        if (store == null) return "{\"scanned\": false}";
        String blueprintHash = computeBlueprintHash(blueprintStr);
        boolean scanned = store.hasBeenScanned(repoStr, commit, blueprintHash);
        return "{\"scanned\": " + scanned + "}";
    }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    private void persistScanResults(String repoStr, String blueprintStr, ScanResult scan) {
        if (store == null) return;
        String blueprintHash = computeBlueprintHash(blueprintStr);
        String blueprintText;
        try { blueprintText = Files.readString(Path.of(blueprintStr)); } catch (Exception e) { blueprintText = ""; }
        List<Snapshot> snapshots = scan.snapshots;
        List<ArchitectureProfile> profiles = scan.profiles;
        for (int i = 0; i < snapshots.size(); i++) {
            Snapshot snap = snapshots.get(i);
            ArchitectureProfile profile = profiles.get(i);
            List<Hotspot> hotspots = profile.moduleMetrics().stream()
                    .filter(m -> m.hotspot() > 0)
                    .map(m -> new Hotspot(m.module().name(), 0, m.wmc(), m.hotspot()))
                    .toList();
            ScanRecord record = new ScanRecord(
                    repoStr, snap.commitId(), snap.timestamp(), blueprintHash,
                    blueprintText,
                    new ArrayList<>(profile.violations()),
                    new ArrayList<>(profile.moduleMetrics()),
                    hotspots,
                    new ArrayList<>(profile.cycles()));
            try {
                store.storeScanResult(record);
            } catch (Exception e) {
                stderr.println("[arx mcp] warning: failed to persist scan: " + e.getMessage());
            }
        }
    }

    private static String computeBlueprintHash(String blueprintPath) {
        try {
            byte[] content = Files.readAllBytes(Path.of(blueprintPath));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String formatViolationTrendFromDb(List<ViolationTrend> trends) {
        List<String> tLines = trends.stream().map(t ->
                "    {\n"
                + "      \"commit\": " + McpJson.escape(t.commitHash()) + ",\n"
                + "      \"timestamp\": " + McpJson.escape(t.timestamp().toString()) + ",\n"
                + "      \"violationCount\": " + t.violationCount() + ",\n"
                + "      \"modules\": {}\n"
                + "    }").toList();
        StringBuilder sb = new StringBuilder("{\n  \"trend\": [\n");
        sb.append(String.join(",\n", tLines));
        if (!tLines.isEmpty()) sb.append("\n");
        sb.append("  ]\n}");
        return sb.toString();
    }

    private static String formatViolationTrendFromScan(ScanResult scan, String moduleFilter) {
        List<Trend.SnapshotEntry> entries = scan.trend.entries();
        List<String> tLines = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Trend.SnapshotEntry entry = entries.get(i);
            Snapshot snap = scan.snapshots.get(i);
            Set<Violation> vs = entry.violations();
            if (moduleFilter != null) {
                vs = vs.stream()
                        .filter(v -> v.dependency().source().name().equals(moduleFilter)
                                  || v.dependency().target().name().equals(moduleFilter))
                        .collect(Collectors.toSet());
            }
            Map<String, Long> perModule = vs.stream().collect(
                    Collectors.groupingBy(v -> v.dependency().source().name(), Collectors.counting()));
            StringBuilder modSb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Long> e : new TreeMap<>(perModule).entrySet()) {
                if (!first) modSb.append(",");
                modSb.append(McpJson.escape(e.getKey())).append(":").append(e.getValue());
                first = false;
            }
            modSb.append("}");
            final int count = vs.size();
            tLines.add("    {\n"
                    + "      \"commit\": " + McpJson.escape(entry.commitId()) + ",\n"
                    + "      \"timestamp\": " + McpJson.escape(snap.timestamp().toString()) + ",\n"
                    + "      \"violationCount\": " + count + ",\n"
                    + "      \"modules\": " + modSb + "\n"
                    + "    }");
        }
        StringBuilder sb = new StringBuilder("{\n  \"trend\": [\n");
        sb.append(String.join(",\n", tLines));
        if (!tLines.isEmpty()) sb.append("\n");
        sb.append("  ]\n}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Shared scan pipeline
    // -------------------------------------------------------------------------

    private record ScanResult(HealthReport report, Trend trend, List<Snapshot> snapshots, Blueprint blueprint, List<ArchitectureProfile> profiles) {}

    private ScanResult buildFullReport(String repoStr, String blueprintStr,
                                       int commitCount, String coverageStr) {
        Path repo = Path.of(repoStr);
        Blueprint blueprint = blueprintSource.load(Path.of(blueprintStr));
        JavaDependencyResolver javaResolver = new JavaDependencyResolver(blueprint.modules());
        CoverageSource coverageSource = buildCoverageSource(
                coverageStr != null ? Path.of(coverageStr) : null);

        GitSnapshotSource snapshotSource = new GitSnapshotSource(
                repo, javaResolver,
                root -> new TypeScriptDependencyResolver(blueprint.modules(), root),
                Language.JAVA, new SnapshotConfig.LastN(commitCount));
        GitHistorySource historySource = new GitHistorySource(
                repo, new SnapshotConfig.LastN(commitCount));

        AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
        List<Snapshot> snapshots = snapshotSource.fetchSnapshots();
        List<CommitEntry> history = historySource.fetchHistory();
        Map<Module, ModuleGitStats> gitStats = new ComputeGitStats().compute(blueprint, history);
        Trend trend = new AnalyzeHistory(analyzeSnapshot).analyze(blueprint, snapshots);
        List<ArchitectureProfile> profiles = snapshots.stream()
                .map(s -> new ComputeMetrics(analyzeSnapshot).compute(blueprint, s, gitStats, coverageSource))
                .toList();
        HealthReport report = new ReportHealth().report(trend, profiles);
        return new ScanResult(report, trend, snapshots, blueprint, profiles);
    }

    private static CoverageSource buildCoverageSource(Path coveragePath) {
        if (coveragePath == null) return null;
        String name = coveragePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".xml"))                               return new JacocoXmlCoverageSource(coveragePath);
        if (name.equals("lcov.info") || name.endsWith(".lcov")) return new LcovCoverageSource(coveragePath);
        return new JacocoXmlCoverageSource(coveragePath);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String requireString(String json, String key) {
        String val = McpJson.getString(json, key);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return val;
    }

    private static boolean fileMatchesModule(List<String> files, Module module) {
        for (String file : files) {
            String norm = file.replace('\\', '/');
            for (String pattern : module.packagePatterns()) {
                String pkgPath = pattern.replace('.', '/');
                if (pkgPath.endsWith("/**")) pkgPath = pkgPath.substring(0, pkgPath.length() - 3);
                if (!pkgPath.isEmpty() && norm.contains(pkgPath)) return true;
            }
            if (norm.toLowerCase().contains(module.name().toLowerCase().replace('-', '/'))) return true;
        }
        return false;
    }

    private static String suggestFix(Violation v) {
        String src = v.dependency().source().name();
        String tgt = v.dependency().target().name();
        int srcLayer = v.dependency().source().layer();
        int tgtLayer = v.dependency().target().layer();
        if (srcLayer >= 0 && tgtLayer >= 0 && srcLayer < tgtLayer) {
            return "Dependency inversion required: '" + src + "' (layer " + srcLayer
                    + ") must not import '" + tgt + "' (layer " + tgtLayer
                    + "). Declare a port interface in '" + src + "' and implement it in '" + tgt + "'.";
        }
        return "Remove the import from '" + src + "' to '" + tgt
                + "', or add 'allow " + src + " -> " + tgt + "' to your blueprint file.";
    }

    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0.0";
        return String.format(Locale.US, "%.4f", d);
    }

    // -------------------------------------------------------------------------
    // JSON-RPC response builders
    // -------------------------------------------------------------------------

    private static String successResponse(String id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
    }

    static String errorResponse(String id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":"
                + code + ",\"message\":" + McpJson.escape(message) + "}}";
    }

    private static String toolResult(String id, String text, boolean isError) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
          .append(",\"result\":{\"content\":[{\"type\":\"text\",\"text\":")
          .append(McpJson.escape(text)).append("}]");
        if (isError) sb.append(",\"isError\":true");
        sb.append("}}");
        return sb.toString();
    }

    private static String toolDef(String name, String description, String inputSchema) {
        return "  {\"name\":" + McpJson.escape(name)
                + ",\"description\":" + McpJson.escape(description)
                + ",\"inputSchema\":" + inputSchema + "}";
    }
}
