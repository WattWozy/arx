package dev.archtelemetry.application.port;

import dev.archtelemetry.domain.CycleTrend;
import dev.archtelemetry.domain.HotspotSnapshot;
import dev.archtelemetry.domain.MetricSnapshot;
import dev.archtelemetry.domain.ScanRecord;
import dev.archtelemetry.domain.ViolationTrend;

import java.util.List;

public interface ScanResultStore {

    /** Persist a complete scan result. Returns the scan ID. */
    long storeScanResult(ScanRecord record);

    /** Violation counts per commit for a module (null = all modules), ordered by commit time desc. */
    List<ViolationTrend> getViolationTrend(String repoPath, String moduleName, int lastN);

    /** Metric history for a module, ordered by commit time desc. */
    List<MetricSnapshot> getMetricHistory(String repoPath, String moduleName, int lastN);

    /** Hotspot history for a file path or module name, ordered by commit time desc. */
    List<HotspotSnapshot> getHotspotHistory(String repoPath, String filePath, int lastN);

    /** Cycle counts per commit, ordered by commit time desc. */
    List<CycleTrend> getCycleHistory(String repoPath, int lastN);

    /** True if this commit was already scanned with this blueprint hash. */
    boolean hasBeenScanned(String repoPath, String commitHash, String blueprintHash);

    /** Distinct repo_path values that have been scanned, ordered alphabetically. */
    List<String> getDistinctRepoPaths();

    /** Full scan record for the most recent commit in the given repo, or null if none. */
    ScanRecord getLatestScanRecord(String repoPath);
}
