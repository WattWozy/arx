package dev.archtelemetry.adapter.java;

import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyResolverTest {

    @TempDir
    Path tempDir;

    private final Module domain = new Module("domain", List.of("dev.archtelemetry.domain.**"));
    private final Module application = new Module("application", List.of("dev.archtelemetry.application.**"));
    private final Module infrastructure = new Module("infrastructure", List.of("dev.archtelemetry.infrastructure.**"));

    private final JavaDependencyResolver resolver = new JavaDependencyResolver(
            Set.of(domain, application, infrastructure)
    );

    private Path file(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void importFromKnownModuleProducesCorrectDependency() throws IOException {
        Path f = file("AppService.java", """
                package dev.archtelemetry.application;
                import dev.archtelemetry.domain.Module;
                class AppService {}
                """);

        assertEquals(Set.of(new Dependency(application, domain)), resolver.resolve(Set.of(f)).dependencies());
    }

    @Test
    void importFromForbiddenModuleProducesCorrectDependency() throws IOException {
        Path f = file("DomainService.java", """
                package dev.archtelemetry.domain;
                import dev.archtelemetry.infrastructure.SomeRepo;
                class DomainService {}
                """);

        assertEquals(Set.of(new Dependency(domain, infrastructure)), resolver.resolve(Set.of(f)).dependencies());
    }

    @Test
    void importsWithinSameModuleAreExcluded() throws IOException {
        Path f = file("OtherDomain.java", """
                package dev.archtelemetry.domain;
                import dev.archtelemetry.domain.Module;
                import dev.archtelemetry.domain.Dependency;
                class OtherDomain {}
                """);

        assertTrue(resolver.resolve(Set.of(f)).dependencies().isEmpty());
    }

    @Test
    void standardLibraryImportsAreIgnored() throws IOException {
        Path f = file("Util.java", """
                package dev.archtelemetry.domain;
                import java.util.List;
                import java.io.IOException;
                import javax.annotation.Nullable;
                class Util {}
                """);

        assertTrue(resolver.resolve(Set.of(f)).dependencies().isEmpty());
    }

    @Test
    void fileWithNoImportsProducesNoDependencies() throws IOException {
        Path f = file("Plain.java", """
                package dev.archtelemetry.domain;
                class Plain {}
                """);

        assertTrue(resolver.resolve(Set.of(f)).dependencies().isEmpty());
    }

    @Test
    void multipleFilesProduceAggregatedDependencySet() throws IOException {
        Path appFile = file("AppService.java", """
                package dev.archtelemetry.application;
                import dev.archtelemetry.domain.Module;
                class AppService {}
                """);
        Path infraFile = file("InfraAdapter.java", """
                package dev.archtelemetry.infrastructure;
                import dev.archtelemetry.application.AnalyzeSnapshot;
                class InfraAdapter {}
                """);

        assertEquals(
                Set.of(
                        new Dependency(application, domain),
                        new Dependency(infrastructure, application)
                ),
                resolver.resolve(Set.of(appFile, infraFile)).dependencies()
        );
    }

    @Test
    void methodCountIsComputedPerModule() throws IOException {
        Path f = file("AppService.java", """
                package dev.archtelemetry.application;
                import dev.archtelemetry.domain.Module;
                class AppService {
                    public void doSomething(String x) {
                    }
                    public int getValue() {
                        return 42;
                    }
                }
                """);

        var resolved = resolver.resolve(Set.of(f));
        int wmc = resolved.moduleWmc().getOrDefault(application, 0);
        assertTrue(wmc >= 2, "Expected at least 2 methods, got " + wmc);
    }
}
