package dev.archtelemetry.adapter.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.archtelemetry.application.port.ScanResultStore;
import dev.archtelemetry.domain.CycleTrend;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.Hotspot;
import dev.archtelemetry.domain.HotspotSnapshot;
import dev.archtelemetry.domain.MetricSnapshot;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.ScanRecord;
import dev.archtelemetry.domain.Violation;
import dev.archtelemetry.domain.ViolationTrend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ArxHttpServer {

    private final ScanResultStore store;
    private final int port;

    public ArxHttpServer(ScanResultStore store, int port) {
        this.store = store;
        this.port = port;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/repos",      this::handleRepos);
        server.createContext("/api/violations",  this::handleViolations);
        server.createContext("/api/metrics",     this::handleMetrics);
        server.createContext("/api/hotspots",    this::handleHotspots);
        server.createContext("/api/cycles",      this::handleCycles);
        server.createContext("/api/snapshot",    this::handleSnapshot);
        server.createContext("/",                this::handleRoot);
        server.start();
        System.out.println("[arx] HTTP server listening on http://localhost:" + port);
    }

    private void handleRepos(HttpExchange ex) throws IOException {
        if (!isGet(ex)) return;
        List<String> repos = store.getDistinctRepoPaths();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < repos.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonStr(repos.get(i)));
        }
        sb.append(']');
        sendJson(ex, 200, sb.toString());
    }

    private void handleViolations(HttpExchange ex) throws IOException {
        if (!isGet(ex)) return;
        Map<String, String> q = parseQuery(ex.getRequestURI());
        String repo = q.get("repo");
        if (repo == null) { sendError(ex, 400, "Missing repo parameter"); return; }
        int limit = parseInt(q.get("limit"), 20);
        List<ViolationTrend> trends = store.getViolationTrend(repo, null, limit);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < trends.size(); i++) {
            if (i > 0) sb.append(',');
            ViolationTrend t = trends.get(i);
            sb.append("{\"commitHash\":").append(jsonStr(t.commitHash()))
              .append(",\"timestamp\":").append(jsonStr(t.timestamp().toString()))
              .append(",\"violationCount\":").append(t.violationCount())
              .append('}');
        }
        sb.append(']');
        sendJson(ex, 200, sb.toString());
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        if (!isGet(ex)) return;
        Map<String, String> q = parseQuery(ex.getRequestURI());
        String repo   = q.get("repo");
        String module = q.get("module");
        if (repo == null || module == null) { sendError(ex, 400, "Missing repo or module parameter"); return; }
        int limit = parseInt(q.get("limit"), 20);
        List<MetricSnapshot> history = store.getMetricHistory(repo, module, limit);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(',');
            MetricSnapshot m = history.get(i);
            sb.append("{\"commitHash\":").append(jsonStr(m.commitHash()))
              .append(",\"timestamp\":").append(jsonStr(m.timestamp().toString()))
              .append(",\"moduleName\":").append(jsonStr(m.moduleName()))
              .append(",\"fanIn\":").append(m.fanIn())
              .append(",\"fanOut\":").append(m.fanOut())
              .append(",\"instability\":").append(m.instability())
              .append(",\"abstractness\":").append(m.abstractness())
              .append(",\"distanceFromMainSequence\":").append(m.distanceFromMainSequence())
              .append(",\"hubScore\":").append(m.hubScore())
              .append(",\"crapScore\":").append(m.crapScore())
              .append(",\"wmc\":").append(m.wmc())
              .append(",\"pageRank\":").append(m.pageRank())
              .append(",\"betweenness\":").append(m.betweenness())
              .append(",\"testDebtScore\":").append(m.testDebtScore())
              .append(",\"churnAcceleration\":").append(m.churnAcceleration())
              .append(",\"busFactorRisk\":").append(m.busFactorRisk())
              .append('}');
        }
        sb.append(']');
        sendJson(ex, 200, sb.toString());
    }

    private void handleHotspots(HttpExchange ex) throws IOException {
        if (!isGet(ex)) return;
        Map<String, String> q = parseQuery(ex.getRequestURI());
        String repo = q.get("repo");
        String file = q.get("file");
        if (repo == null || file == null) { sendError(ex, 400, "Missing repo or file parameter"); return; }
        int limit = parseInt(q.get("limit"), 20);
        List<HotspotSnapshot> history = store.getHotspotHistory(repo, file, limit);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(',');
            HotspotSnapshot h = history.get(i);
            sb.append("{\"commitHash\":").append(jsonStr(h.commitHash()))
              .append(",\"timestamp\":").append(jsonStr(h.timestamp().toString()))
              .append(",\"filePath\":").append(jsonStr(h.filePath()))
              .append(",\"churn\":").append(h.churn())
              .append(",\"complexity\":").append(h.complexity())
              .append(",\"score\":").append(h.score())
              .append('}');
        }
        sb.append(']');
        sendJson(ex, 200, sb.toString());
    }

    private void handleCycles(HttpExchange ex) throws IOException {
        if (!isGet(ex)) return;
        Map<String, String> q = parseQuery(ex.getRequestURI());
        String repo = q.get("repo");
        if (repo == null) { sendError(ex, 400, "Missing repo parameter"); return; }
        int limit = parseInt(q.get("limit"), 20);
        List<CycleTrend> history = store.getCycleHistory(repo, limit);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(',');
            CycleTrend c = history.get(i);
            sb.append("{\"commitHash\":").append(jsonStr(c.commitHash()))
              .append(",\"timestamp\":").append(jsonStr(c.timestamp().toString()))
              .append(",\"cycleCount\":").append(c.cycleCount())
              .append('}');
        }
        sb.append(']');
        sendJson(ex, 200, sb.toString());
    }

    private void handleSnapshot(HttpExchange ex) throws IOException {
        if (!isGet(ex)) return;
        Map<String, String> q = parseQuery(ex.getRequestURI());
        String repo = q.get("repo");
        if (repo == null) { sendError(ex, 400, "Missing repo parameter"); return; }
        ScanRecord rec = store.getLatestScanRecord(repo);
        if (rec == null) { sendJson(ex, 200, "null"); return; }

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"repoPath\":").append(jsonStr(rec.repoPath()))
          .append(",\"commitHash\":").append(jsonStr(rec.commitHash()))
          .append(",\"commitTime\":").append(jsonStr(rec.commitTime().toString()))
          .append(",\"violations\":[");

        List<Violation> violations = rec.violations();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) sb.append(',');
            Violation v = violations.get(i);
            sb.append("{\"source\":").append(jsonStr(v.dependency().source().name()))
              .append(",\"target\":").append(jsonStr(v.dependency().target().name()))
              .append('}');
        }
        sb.append("],\"moduleMetrics\":[");

        List<ModuleMetrics> metrics = rec.moduleMetrics();
        for (int i = 0; i < metrics.size(); i++) {
            if (i > 0) sb.append(',');
            ModuleMetrics m = metrics.get(i);
            sb.append("{\"module\":").append(jsonStr(m.module().name()))
              .append(",\"fanIn\":").append(m.fanIn())
              .append(",\"fanOut\":").append(m.fanOut())
              .append(",\"instability\":").append(m.instability())
              .append(",\"abstractness\":").append(m.abstractness())
              .append(",\"distanceFromMainSequence\":").append(m.distanceFromMainSequence())
              .append(",\"wmc\":").append(m.wmc())
              .append(",\"hotspot\":").append(m.hotspot())
              .append(",\"churnAcceleration\":").append(m.churnAcceleration())
              .append(",\"busFactorRisk\":").append(m.busFactorRisk())
              .append(",\"crapScore\":").append(m.crapScore())
              .append(",\"testDebtScore\":").append(m.testDebtScore())
              .append(",\"pageRank\":").append(m.pageRank())
              .append(",\"betweenness\":").append(m.betweenness())
              .append(",\"hubScore\":").append(m.hubScore())
              .append('}');
        }
        sb.append("],\"hotspots\":[");

        List<Hotspot> hotspots = rec.hotspots();
        for (int i = 0; i < hotspots.size(); i++) {
            if (i > 0) sb.append(',');
            Hotspot h = hotspots.get(i);
            sb.append("{\"filePath\":").append(jsonStr(h.filePath()))
              .append(",\"churn\":").append(h.churn())
              .append(",\"complexity\":").append(h.complexity())
              .append(",\"score\":").append(h.score())
              .append('}');
        }
        sb.append("],\"cycles\":[");

        List<DependencyCycle> cycles = rec.cycles();
        for (int i = 0; i < cycles.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            List<Module> mods = cycles.get(i).modules();
            for (int j = 0; j < mods.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(jsonStr(mods.get(j).name()));
            }
            sb.append(']');
        }
        sb.append("]}");
        sendJson(ex, 200, sb.toString());
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!isGet(ex)) return;
        String path = ex.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) { sendError(ex, 404, "Not Found"); return; }
        try (InputStream in = ArxHttpServer.class.getResourceAsStream("/index.html")) {
            if (in == null) { sendError(ex, 404, "index.html not found on classpath"); return; }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        }
    }

    private boolean isGet(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod())) return true;
        sendError(ex, 405, "Method Not Allowed");
        return false;
    }

    private void sendJson(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private void sendError(HttpExchange ex, int code, String msg) throws IOException {
        sendJson(ex, code, "{\"error\":" + jsonStr(msg) + "}");
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return map;
        for (String param : raw.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(param.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}
