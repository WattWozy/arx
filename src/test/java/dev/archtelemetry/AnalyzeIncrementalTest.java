package dev.archtelemetry;

import dev.archtelemetry.adapter.java.JavaDependencyResolver;
import dev.archtelemetry.application.port.LocatedDependency;
import dev.archtelemetry.application.port.ResolvedDataWithLocations;
import dev.archtelemetry.application.AnalyzeIncremental;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.IncrementalResult;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzeIncrementalTest {

    @TempDir
    Path tempDir;

    private final Module domain = new Module("domain", List.of("dev.example.domain.**"), 0);
    private final Module application = new Module("application", List.of("dev.example.application.**"), 1);
    private final Module adapter = new Module("adapter", List.of("dev.example.adapter.**"), 2);

    private final JavaDependencyResolver resolver = new JavaDependencyResolver(
            Set.of(domain, application, adapter));
    private final AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
    private final AnalyzeIncremental analyzeIncremental =
            new AnalyzeIncremental(resolver, analyzeSnapshot);

    private Blueprint cleanBlueprint() {
        return new Blueprint(
                Set.of(domain, application, adapter),
                Set.of(new Dependency(application, domain), new Dependency(adapter, application)));
    }

    private Path writeFile(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void cleanChangeProducesNoNewViolations() throws IOException {
        // Baseline: application -> domain (allowed)
        Snapshot baseline = new Snapshot("head", Instant.now(),
                Set.of(new Dependency(application, domain)), Map.of());

        Path changed = writeFile("AppService.java", """
                package dev.example.application;
                import dev.example.domain.Entity;
                class AppService {}
                """);

        IncrementalResult result = analyzeIncremental.analyze(Set.of(changed), cleanBlueprint(), baseline);

        assertTrue(result.newViolations().isEmpty(), "No new violations expected for allowed dep");
    }

    @Test
    void newForbiddenImportProducesViolation() throws IOException {
        // Baseline: clean (domain has no deps)
        Snapshot baseline = new Snapshot("head", Instant.now(), Set.of(), Map.of());

        Path changed = writeFile("Entity.java", """
                package dev.example.domain;
                import dev.example.adapter.SomeAdapter;
                class Entity {}
                """);

        IncrementalResult result = analyzeIncremental.analyze(Set.of(changed), cleanBlueprint(), baseline);

        assertEquals(1, result.newViolations().size());
        Violation v = result.newViolations().iterator().next();
        assertEquals(domain, v.dependency().source());
        assertEquals(adapter, v.dependency().target());
    }

    @Test
    void preExistingViolationNotReportedAsNew() throws IOException {
        // Baseline already has the violation
        Dependency forbidden = new Dependency(domain, adapter);
        Snapshot baseline = new Snapshot("head", Instant.now(), Set.of(forbidden), Map.of());

        // Change file in domain — same forbidden import
        Path changed = writeFile("Entity.java", """
                package dev.example.domain;
                import dev.example.adapter.SomeAdapter;
                class Entity {}
                """);

        IncrementalResult result = analyzeIncremental.analyze(Set.of(changed), cleanBlueprint(), baseline);

        assertTrue(result.newViolations().isEmpty(),
                "Pre-existing violation must not appear as new");
        assertEquals(1, result.allViolations().size());
    }

    @Test
    void fixingViolationProducesNoViolationsInResult() throws IOException {
        // Baseline had a violation (domain -> adapter)
        Snapshot baseline = new Snapshot("head", Instant.now(),
                Set.of(new Dependency(domain, adapter)), Map.of());

        // Changed file now clean
        Path changed = writeFile("Entity.java", """
                package dev.example.domain;
                class Entity {}
                """);

        IncrementalResult result = analyzeIncremental.analyze(Set.of(changed), cleanBlueprint(), baseline);

        assertTrue(result.allViolations().isEmpty());
        assertTrue(result.newViolations().isEmpty());
    }

    @Test
    void resolveWithLocationsTracksFileAndLine() throws IOException {
        Path f = writeFile("Entity.java", """
                package dev.example.domain;
                import dev.example.adapter.SomeAdapter;
                class Entity {}
                """);

        ResolvedDataWithLocations located = resolver.resolveWithLocations(Set.of(f));

        assertEquals(1, located.locatedDependencies().size());
        LocatedDependency loc = located.locatedDependencies().get(0);
        assertEquals(domain, loc.dependency().source());
        assertEquals(adapter, loc.dependency().target());
        assertEquals(2, loc.lineNumber()); // line 2 is the import
        assertEquals(f, loc.sourceFile());
        assertTrue(loc.importText().contains("dev.example.adapter"));
    }
}
