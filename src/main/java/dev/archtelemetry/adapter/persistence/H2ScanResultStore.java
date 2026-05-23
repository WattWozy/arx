package dev.archtelemetry.adapter.persistence;

import dev.archtelemetry.application.port.ScanResultStore;
import dev.archtelemetry.domain.CycleTrend;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.Hotspot;
import dev.archtelemetry.domain.HotspotSnapshot;
import dev.archtelemetry.domain.MetricSnapshot;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.ScanRecord;
import dev.archtelemetry.domain.Violation;
import dev.archtelemetry.domain.ViolationTrend;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class H2ScanResultStore implements ScanResultStore {

    private final String url;

    public H2ScanResultStore(String url) {
        this.url = url;
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("H2 driver not found on classpath", e);
        }
        initSchema();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void initSchema() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS scan_results (
                    id             INTEGER AUTO_INCREMENT PRIMARY KEY,
                    repo_path      VARCHAR(1024) NOT NULL,
                    commit_hash    VARCHAR(40) NOT NULL,
                    commit_time    TIMESTAMP NOT NULL,
                    blueprint_hash VARCHAR(64) NOT NULL,
                    blueprint_text TEXT,
                    scanned_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(repo_path, commit_hash, blueprint_hash)
                )
                """);
            stmt.execute("ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS blueprint_text TEXT");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS violations (
                    id             INTEGER AUTO_INCREMENT PRIMARY KEY,
                    scan_id        INTEGER NOT NULL REFERENCES scan_results(id),
                    source_module  VARCHAR(256) NOT NULL,
                    target_module  VARCHAR(256) NOT NULL,
                    source_file    VARCHAR(1024),
                    line_number    INTEGER,
                    suggested_fix  VARCHAR(2048)
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_violations_scan ON violations(scan_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_violations_modules ON violations(source_module, target_module)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS module_metrics (
                    id                INTEGER AUTO_INCREMENT PRIMARY KEY,
                    scan_id           INTEGER NOT NULL REFERENCES scan_results(id),
                    module_name       VARCHAR(256) NOT NULL,
                    fan_in            INTEGER,
                    fan_out           INTEGER,
                    instability       DOUBLE,
                    abstractness      DOUBLE,
                    distance          DOUBLE,
                    hub_score         DOUBLE,
                    crap_score        DOUBLE,
                    wmc               INTEGER,
                    page_rank         DOUBLE,
                    betweenness       DOUBLE,
                    test_debt_score   DOUBLE,
                    churn_acceleration DOUBLE,
                    bus_factor_risk   DOUBLE
                )
                """);
            stmt.execute("ALTER TABLE module_metrics ADD COLUMN IF NOT EXISTS test_debt_score DOUBLE");
            stmt.execute("ALTER TABLE module_metrics ADD COLUMN IF NOT EXISTS churn_acceleration DOUBLE");
            stmt.execute("ALTER TABLE module_metrics ADD COLUMN IF NOT EXISTS bus_factor_risk DOUBLE");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_scan ON module_metrics(scan_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_module ON module_metrics(module_name)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS hotspots (
                    id            INTEGER AUTO_INCREMENT PRIMARY KEY,
                    scan_id       INTEGER NOT NULL REFERENCES scan_results(id),
                    file_path     VARCHAR(1024) NOT NULL,
                    churn         INTEGER,
                    complexity    INTEGER,
                    score         DOUBLE
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_hotspots_scan ON hotspots(scan_id)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS cycles (
                    id          INTEGER AUTO_INCREMENT PRIMARY KEY,
                    scan_id     INTEGER NOT NULL REFERENCES scan_results(id),
                    cycle_id    INT NOT NULL,
                    module_name VARCHAR(256) NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_cycles_scan ON cycles(scan_id)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize DB schema", e);
        }
    }

    @Override
    public long storeScanResult(ScanRecord record) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                long scanId = insertScanResult(conn, record);
                insertViolations(conn, scanId, record);
                insertModuleMetrics(conn, scanId, record);
                insertHotspots(conn, scanId, record);
                insertCycles(conn, scanId, record);
                conn.commit();
                return scanId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store scan result", e);
        }
    }

    private long insertScanResult(Connection conn, ScanRecord record) throws SQLException {
        // H2 proprietary MERGE: inserts if KEY not present, updates otherwise (idempotent)
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO scan_results (repo_path, commit_hash, commit_time, blueprint_hash, blueprint_text) " +
                "KEY (repo_path, commit_hash, blueprint_hash) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, record.repoPath());
            ps.setString(2, record.commitHash());
            ps.setTimestamp(3, Timestamp.from(record.commitTime()));
            ps.setString(4, record.blueprintHash());
            ps.setString(5, record.blueprintText());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM scan_results WHERE repo_path=? AND commit_hash=? AND blueprint_hash=?")) {
            ps.setString(1, record.repoPath());
            ps.setString(2, record.commitHash());
            ps.setString(3, record.blueprintHash());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Could not resolve scan_results id after merge");
    }

    private void insertViolations(Connection conn, long scanId, ScanRecord record) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM violations WHERE scan_id=?")) {
            del.setLong(1, scanId);
            del.executeUpdate();
        }
        if (record.violations().isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO violations (scan_id, source_module, target_module) VALUES (?, ?, ?)")) {
            for (var v : record.violations()) {
                ps.setLong(1, scanId);
                ps.setString(2, v.dependency().source().name());
                ps.setString(3, v.dependency().target().name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertModuleMetrics(Connection conn, long scanId, ScanRecord record) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM module_metrics WHERE scan_id=?")) {
            del.setLong(1, scanId);
            del.executeUpdate();
        }
        if (record.moduleMetrics().isEmpty()) return;
        String sql = "INSERT INTO module_metrics " +
                "(scan_id, module_name, fan_in, fan_out, instability, abstractness, distance, " +
                "hub_score, crap_score, wmc, page_rank, betweenness, " +
                "test_debt_score, churn_acceleration, bus_factor_risk) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ModuleMetrics m : record.moduleMetrics()) {
                ps.setLong(1, scanId);
                ps.setString(2, m.module().name());
                ps.setInt(3, m.fanIn());
                ps.setInt(4, m.fanOut());
                ps.setDouble(5, m.instability());
                ps.setDouble(6, m.abstractness());
                ps.setDouble(7, m.distanceFromMainSequence());
                ps.setDouble(8, m.hubScore());
                ps.setDouble(9, m.crapScore());
                ps.setInt(10, m.wmc());
                ps.setDouble(11, m.pageRank());
                ps.setDouble(12, m.betweenness());
                ps.setDouble(13, m.testDebtScore());
                ps.setDouble(14, m.churnAcceleration());
                ps.setDouble(15, m.busFactorRisk());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertHotspots(Connection conn, long scanId, ScanRecord record) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM hotspots WHERE scan_id=?")) {
            del.setLong(1, scanId);
            del.executeUpdate();
        }
        if (record.hotspots().isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO hotspots (scan_id, file_path, churn, complexity, score) VALUES (?, ?, ?, ?, ?)")) {
            for (var h : record.hotspots()) {
                ps.setLong(1, scanId);
                ps.setString(2, h.filePath());
                ps.setInt(3, h.churn());
                ps.setInt(4, h.complexity());
                ps.setDouble(5, h.score());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertCycles(Connection conn, long scanId, ScanRecord record) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM cycles WHERE scan_id=?")) {
            del.setLong(1, scanId);
            del.executeUpdate();
        }
        if (record.cycles().isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cycles (scan_id, cycle_id, module_name) VALUES (?, ?, ?)")) {
            for (int i = 0; i < record.cycles().size(); i++) {
                for (var mod : record.cycles().get(i).modules()) {
                    ps.setLong(1, scanId);
                    ps.setInt(2, i);
                    ps.setString(3, mod.name());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    @Override
    public List<ViolationTrend> getViolationTrend(String repoPath, String moduleName, int lastN) {
        String sql;
        if (moduleName == null) {
            sql = "SELECT sr.commit_hash, sr.commit_time, COUNT(v.id) AS vc " +
                  "FROM scan_results sr LEFT JOIN violations v ON v.scan_id = sr.id " +
                  "WHERE sr.repo_path = ? " +
                  "GROUP BY sr.id, sr.commit_hash, sr.commit_time " +
                  "ORDER BY sr.commit_time DESC LIMIT ?";
        } else {
            sql = "SELECT sr.commit_hash, sr.commit_time, COUNT(v.id) AS vc " +
                  "FROM scan_results sr LEFT JOIN violations v ON v.scan_id = sr.id " +
                  "  AND (v.source_module = ? OR v.target_module = ?) " +
                  "WHERE sr.repo_path = ? " +
                  "GROUP BY sr.id, sr.commit_hash, sr.commit_time " +
                  "ORDER BY sr.commit_time DESC LIMIT ?";
        }
        List<ViolationTrend> result = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (moduleName == null) {
                ps.setString(1, repoPath);
                ps.setInt(2, lastN);
            } else {
                ps.setString(1, moduleName);
                ps.setString(2, moduleName);
                ps.setString(3, repoPath);
                ps.setInt(4, lastN);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ViolationTrend(
                            rs.getString("commit_hash"),
                            rs.getTimestamp("commit_time").toInstant(),
                            rs.getInt("vc")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query violation trend", e);
        }
        return result;
    }

    @Override
    public List<MetricSnapshot> getMetricHistory(String repoPath, String moduleName, int lastN) {
        String sql = "SELECT sr.commit_hash, sr.commit_time, mm.module_name, mm.fan_in, mm.fan_out, " +
                     "mm.instability, mm.abstractness, mm.distance, mm.hub_score, mm.crap_score, " +
                     "mm.wmc, mm.page_rank, mm.betweenness, " +
                     "mm.test_debt_score, mm.churn_acceleration, mm.bus_factor_risk " +
                     "FROM scan_results sr JOIN module_metrics mm ON mm.scan_id = sr.id AND mm.module_name = ? " +
                     "WHERE sr.repo_path = ? ORDER BY sr.commit_time DESC LIMIT ?";
        List<MetricSnapshot> result = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, moduleName);
            ps.setString(2, repoPath);
            ps.setInt(3, lastN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new MetricSnapshot(
                            rs.getString("commit_hash"),
                            rs.getTimestamp("commit_time").toInstant(),
                            rs.getString("module_name"),
                            rs.getInt("fan_in"),
                            rs.getInt("fan_out"),
                            rs.getDouble("instability"),
                            rs.getDouble("abstractness"),
                            rs.getDouble("distance"),
                            rs.getDouble("hub_score"),
                            rs.getDouble("crap_score"),
                            rs.getInt("wmc"),
                            rs.getDouble("page_rank"),
                            rs.getDouble("betweenness"),
                            rs.getDouble("test_debt_score"),
                            rs.getDouble("churn_acceleration"),
                            rs.getDouble("bus_factor_risk")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query metric history", e);
        }
        return result;
    }

    @Override
    public List<HotspotSnapshot> getHotspotHistory(String repoPath, String filePath, int lastN) {
        String sql = "SELECT sr.commit_hash, sr.commit_time, h.file_path, h.churn, h.complexity, h.score " +
                     "FROM scan_results sr JOIN hotspots h ON h.scan_id = sr.id AND h.file_path = ? " +
                     "WHERE sr.repo_path = ? ORDER BY sr.commit_time DESC LIMIT ?";
        List<HotspotSnapshot> result = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            ps.setString(2, repoPath);
            ps.setInt(3, lastN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new HotspotSnapshot(
                            rs.getString("commit_hash"),
                            rs.getTimestamp("commit_time").toInstant(),
                            rs.getString("file_path"),
                            rs.getInt("churn"),
                            rs.getInt("complexity"),
                            rs.getDouble("score")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query hotspot history", e);
        }
        return result;
    }

    @Override
    public List<CycleTrend> getCycleHistory(String repoPath, int lastN) {
        String sql = "SELECT sr.commit_hash, sr.commit_time, COUNT(DISTINCT c.cycle_id) AS cc " +
                     "FROM scan_results sr LEFT JOIN cycles c ON c.scan_id = sr.id " +
                     "WHERE sr.repo_path = ? " +
                     "GROUP BY sr.id, sr.commit_hash, sr.commit_time " +
                     "ORDER BY sr.commit_time DESC LIMIT ?";
        List<CycleTrend> result = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoPath);
            ps.setInt(2, lastN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new CycleTrend(
                            rs.getString("commit_hash"),
                            rs.getTimestamp("commit_time").toInstant(),
                            rs.getInt("cc")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query cycle history", e);
        }
        return result;
    }

    @Override
    public List<String> getDistinctRepoPaths() {
        String sql = "SELECT DISTINCT repo_path FROM scan_results ORDER BY repo_path";
        List<String> result = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(rs.getString("repo_path"));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query repo paths", e);
        }
        return result;
    }

    @Override
    public ScanRecord getLatestScanRecord(String repoPath) {
        long scanId;
        String commitHash;
        java.time.Instant commitTime;
        String blueprintHash;
        String blueprintText;

        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, commit_hash, commit_time, blueprint_hash, blueprint_text " +
                    "FROM scan_results WHERE repo_path = ? ORDER BY commit_time DESC LIMIT 1")) {
                ps.setString(1, repoPath);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    scanId      = rs.getLong("id");
                    commitHash  = rs.getString("commit_hash");
                    commitTime  = rs.getTimestamp("commit_time").toInstant();
                    blueprintHash = rs.getString("blueprint_hash");
                    blueprintText = rs.getString("blueprint_text");
                }
            }

            List<Violation> violations = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT source_module, target_module FROM violations WHERE scan_id = ?")) {
                ps.setLong(1, scanId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) violations.add(new Violation(new Dependency(
                            new Module(rs.getString("source_module")),
                            new Module(rs.getString("target_module")))));
                }
            }

            List<ModuleMetrics> metrics = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT module_name, fan_in, fan_out, instability, abstractness, distance, " +
                    "hub_score, crap_score, wmc, page_rank, betweenness, " +
                    "test_debt_score, churn_acceleration, bus_factor_risk " +
                    "FROM module_metrics WHERE scan_id = ?")) {
                ps.setLong(1, scanId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        metrics.add(new ModuleMetrics(
                                new Module(rs.getString("module_name")),
                                rs.getInt("fan_in"), rs.getInt("fan_out"),
                                rs.getDouble("instability"), rs.getDouble("abstractness"),
                                rs.getDouble("distance"), rs.getInt("wmc"), 0.0,
                                rs.getDouble("churn_acceleration"), rs.getDouble("bus_factor_risk"),
                                rs.getDouble("crap_score"), rs.getDouble("test_debt_score"),
                                rs.getDouble("page_rank"), rs.getDouble("betweenness"),
                                rs.getDouble("hub_score")));
                    }
                }
            }

            List<Hotspot> hotspots = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT file_path, churn, complexity, score FROM hotspots WHERE scan_id = ?")) {
                ps.setLong(1, scanId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) hotspots.add(new Hotspot(
                            rs.getString("file_path"), rs.getInt("churn"),
                            rs.getInt("complexity"), rs.getDouble("score")));
                }
            }

            List<DependencyCycle> cycles = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT cycle_id, module_name FROM cycles WHERE scan_id = ? ORDER BY cycle_id, module_name")) {
                ps.setLong(1, scanId);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<Integer, List<Module>> byId = new LinkedHashMap<>();
                    while (rs.next()) byId
                            .computeIfAbsent(rs.getInt("cycle_id"), k -> new ArrayList<>())
                            .add(new Module(rs.getString("module_name")));
                    byId.values().forEach(mods -> cycles.add(new DependencyCycle(mods)));
                }
            }

            return new ScanRecord(repoPath, commitHash, commitTime, blueprintHash, blueprintText,
                    violations, metrics, hotspots, cycles);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query latest scan record", e);
        }
    }

    @Override
    public boolean hasBeenScanned(String repoPath, String commitHash, String blueprintHash) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM scan_results WHERE repo_path=? AND commit_hash=? AND blueprint_hash=?")) {
            ps.setString(1, repoPath);
            ps.setString(2, commitHash);
            ps.setString(3, blueprintHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }
}
