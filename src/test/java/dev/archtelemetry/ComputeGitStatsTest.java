package dev.archtelemetry;

import dev.archtelemetry.application.ComputeGitStats;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.CommitEntry;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleGitStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeGitStatsTest {

    private final ComputeGitStats computeGitStats = new ComputeGitStats();

    private final Module domain = new Module("domain", List.of("com.example.domain.**"));
    private final Module service = new Module("service", List.of("com.example.service.**"));
    private final Blueprint blueprint = new Blueprint(Set.of(domain, service), Set.of());

    private final Instant now = Instant.now();

    @Test
    void commitCountAggregatedPerModule() {
        List<CommitEntry> history = List.of(
                new CommitEntry("c1", "alice@x.com", now.minus(5, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java")),
                new CommitEntry("c2", "bob@x.com", now.minus(3, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Bar.java")),
                new CommitEntry("c3", "alice@x.com", now.minus(1, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/service/Svc.java"))
        );

        Map<Module, ModuleGitStats> stats = computeGitStats.compute(blueprint, history);

        assertEquals(2, stats.get(domain).commitCount());
        assertEquals(1, stats.get(service).commitCount());
    }

    @Test
    void distinctAuthorCountPerModule() {
        List<CommitEntry> history = List.of(
                new CommitEntry("c1", "alice@x.com", now.minus(2, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java")),
                new CommitEntry("c2", "alice@x.com", now.minus(1, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java")),
                new CommitEntry("c3", "bob@x.com", now.minus(1, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java"))
        );

        Map<Module, ModuleGitStats> stats = computeGitStats.compute(blueprint, history);

        assertEquals(2, stats.get(domain).authorCount());
        assertEquals(2, stats.get(domain).authorEmails().size());
    }

    @Test
    void busFactorRiskHighWhenOneAuthorDominates() {
        List<CommitEntry> history = List.of(
                new CommitEntry("c1", "alice@x.com", now.minus(5, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java")),
                new CommitEntry("c2", "alice@x.com", now.minus(4, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java")),
                new CommitEntry("c3", "alice@x.com", now.minus(3, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java")),
                new CommitEntry("c4", "alice@x.com", now.minus(2, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java")),
                new CommitEntry("c5", "bob@x.com", now.minus(1, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/domain/Foo.java"))
        );

        Map<Module, ModuleGitStats> stats = computeGitStats.compute(blueprint, history);
        ModuleGitStats domainStats = stats.get(domain);

        // 5 commits, 2 authors: busFactorRisk = 5/2 = 2.5
        assertEquals(2.5, domainStats.busFactorRisk(), 0.001);
    }

    @Test
    void emptyHistoryProducesEmptyMap() {
        Map<Module, ModuleGitStats> stats = computeGitStats.compute(blueprint, List.of());
        assertTrue(stats.isEmpty());
    }

    @Test
    void moduleWithNoCommitsHasZeroStats() {
        List<CommitEntry> history = List.of(
                new CommitEntry("c1", "alice@x.com", now.minus(1, ChronoUnit.DAYS),
                        Set.of("src/main/java/com/example/service/Svc.java"))
        );

        Map<Module, ModuleGitStats> stats = computeGitStats.compute(blueprint, history);

        assertEquals(0, stats.get(domain).commitCount());
        assertEquals(0, stats.get(domain).authorCount());
    }
}
