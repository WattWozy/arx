package dev.archtelemetry.adapter.persistence;

import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Hotspot;
import dev.archtelemetry.domain.HotspotSnapshot;
import dev.archtelemetry.domain.MetricSnapshot;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.ScanRecord;
import dev.archtelemetry.domain.Violation;
import dev.archtelemetry.domain.ViolationTrend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class H2ScanResultStoreTest {

    private static final String URL = "jdbc:h2:mem:arx_test;DB_CLOSE_DELAY=-1";

    private H2ScanResultStore store;

    private final Module src = new Module("source");
    private final Module tgt = new Module("target");

    @BeforeEach
    void setUp() {
        store = new H2ScanResultStore(URL);
    }

    @Test
    void storeThenRetrieveViolationTrend() {
        ScanRecord record = scanRecord("repo1", "abc123", Instant.ofEpochSecond(1000),
                List.of(violation(src, tgt)), List.of(), List.of());
        store.storeScanResult(record);

        List<ViolationTrend> trend = store.getViolationTrend("repo1", null, 10);
        assertEquals(1, trend.size());
        assertEquals("abc123", trend.get(0).commitHash());
        assertEquals(1, trend.get(0).violationCount());
    }

    @Test
    void multipleScansOrderedByCommitTime() {
        Instant t1 = Instant.ofEpochSecond(1000);
        Instant t2 = Instant.ofEpochSecond(2000);
        Instant t3 = Instant.ofEpochSecond(3000);

        store.storeScanResult(scanRecord("repo2", "commit-a", t1, List.of(violation(src, tgt)), List.of(), List.of()));
        store.storeScanResult(scanRecord("repo2", "commit-b", t2, List.of(), List.of(), List.of()));
        store.storeScanResult(scanRecord("repo2", "commit-c", t3, List.of(violation(src, tgt), violation(tgt, src)), List.of(), List.of()));

        List<ViolationTrend> trend = store.getViolationTrend("repo2", null, 10);
        assertEquals(3, trend.size());
        // ordered desc by commit_time
        assertEquals("commit-c", trend.get(0).commitHash());
        assertEquals(2, trend.get(0).violationCount());
        assertEquals("commit-b", trend.get(1).commitHash());
        assertEquals(0, trend.get(1).violationCount());
        assertEquals("commit-a", trend.get(2).commitHash());
        assertEquals(1, trend.get(2).violationCount());
    }

    @Test
    void hasBeenScannedReturnsTrueAndFalse() {
        store.storeScanResult(scanRecord("repo3", "def456", Instant.now(), List.of(), List.of(), List.of()));

        assertTrue(store.hasBeenScanned("repo3", "def456", "hash1"));
        assertFalse(store.hasBeenScanned("repo3", "unknown", "hash1"));
        assertFalse(store.hasBeenScanned("other-repo", "def456", "hash1"));
    }

    @Test
    void duplicateScanHandledGracefully() {
        ScanRecord r = scanRecord("repo4", "ghi789", Instant.now(), List.of(), List.of(), List.of());
        long id1 = store.storeScanResult(r);
        long id2 = store.storeScanResult(r);
        assertEquals(id1, id2);
    }

    @Test
    void metricHistoryCorrectValuesInOrder() {
        Module m = new Module("mymodule");
        ModuleMetrics metrics1 = ModuleMetrics.compute(m, 2, 3);
        ModuleMetrics metrics2 = ModuleMetrics.compute(m, 4, 1);

        store.storeScanResult(scanRecord("repo5", "commit-x", Instant.ofEpochSecond(1000),
                List.of(), List.of(metrics1), List.of()));
        store.storeScanResult(scanRecord("repo5", "commit-y", Instant.ofEpochSecond(2000),
                List.of(), List.of(metrics2), List.of()));

        List<MetricSnapshot> history = store.getMetricHistory("repo5", "mymodule", 10);
        assertEquals(2, history.size());
        // ordered desc
        assertEquals("commit-y", history.get(0).commitHash());
        assertEquals(4, history.get(0).fanIn());
        assertEquals("commit-x", history.get(1).commitHash());
        assertEquals(2, history.get(1).fanIn());
    }

    @Test
    void hotspotHistoryCorrectValuesInOrder() {
        Hotspot h1 = new Hotspot("domain", 5, 10, 50.0);
        Hotspot h2 = new Hotspot("domain", 8, 12, 96.0);

        store.storeScanResult(scanRecord("repo6", "commit-p", Instant.ofEpochSecond(1000),
                List.of(), List.of(), List.of(h1)));
        store.storeScanResult(scanRecord("repo6", "commit-q", Instant.ofEpochSecond(2000),
                List.of(), List.of(), List.of(h2)));

        List<HotspotSnapshot> history = store.getHotspotHistory("repo6", "domain", 10);
        assertEquals(2, history.size());
        assertEquals("commit-q", history.get(0).commitHash());
        assertEquals(96.0, history.get(0).score(), 0.001);
        assertEquals("commit-p", history.get(1).commitHash());
        assertEquals(50.0, history.get(1).score(), 0.001);
    }

    @Test
    void violationTrendModuleFilter() {
        Module a = new Module("alpha");
        Module b = new Module("beta");
        Module c = new Module("gamma");

        store.storeScanResult(scanRecord("repo7", "c1", Instant.ofEpochSecond(1000),
                List.of(violation(a, b), violation(c, b)), List.of(), List.of()));

        List<ViolationTrend> all = store.getViolationTrend("repo7", null, 10);
        assertEquals(2, all.get(0).violationCount());

        List<ViolationTrend> filtered = store.getViolationTrend("repo7", "alpha", 10);
        assertEquals(1, filtered.get(0).violationCount());
    }

    // -------------------------------------------------------------------------

    private static ScanRecord scanRecord(String repo, String commit, Instant time,
                                         List<Violation> violations,
                                         List<ModuleMetrics> metrics,
                                         List<Hotspot> hotspots) {
        return new ScanRecord(repo, commit, time, "hash1", "",
                violations, metrics, hotspots, List.of());
    }

    private static Violation violation(Module from, Module to) {
        return new Violation(new Dependency(from, to));
    }
}
