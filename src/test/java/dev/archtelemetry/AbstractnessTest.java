package dev.archtelemetry;

import dev.archtelemetry.adapter.java.JavaDependencyResolver;
import dev.archtelemetry.application.port.ResolvedData;
import dev.archtelemetry.domain.Module;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractnessTest {

    @TempDir
    Path tempDir;

    private final Module domain = new Module("domain", List.of("dev.example.domain.**"), 0);
    private final JavaDependencyResolver resolver = new JavaDependencyResolver(Set.of(domain));

    @Test
    void interfaceOnlyModuleGetsAbstractnessOne() throws IOException {
        Path f = tempDir.resolve("Port.java");
        Files.writeString(f, """
                package dev.example.domain;
                public interface Port {
                    void execute();
                }
                """);

        ResolvedData data = resolver.resolve(Set.of(f));

        double abstractness = data.moduleAbstractness().getOrDefault(domain, 0.0);
        assertEquals(1.0, abstractness, 0.001, "All-interface module should have abstractness=1.0");
    }

    @Test
    void concreteOnlyModuleGetsAbstractnessZero() throws IOException {
        Path f = tempDir.resolve("Service.java");
        Files.writeString(f, """
                package dev.example.domain;
                public class Service {
                    public void run() {}
                }
                """);

        ResolvedData data = resolver.resolve(Set.of(f));

        double abstractness = data.moduleAbstractness().getOrDefault(domain, 0.0);
        assertEquals(0.0, abstractness, 0.001, "All-class module should have abstractness=0.0");
    }

    @Test
    void mixedModuleGetsPartialAbstractness() throws IOException {
        Path iface = tempDir.resolve("Port.java");
        Files.writeString(iface, """
                package dev.example.domain;
                public interface Port {}
                """);
        Path impl = tempDir.resolve("Impl.java");
        Files.writeString(impl, """
                package dev.example.domain;
                public class Impl implements Port {}
                """);

        ResolvedData data = resolver.resolve(Set.of(iface, impl));

        double abstractness = data.moduleAbstractness().getOrDefault(domain, 0.0);
        assertEquals(0.5, abstractness, 0.001, "1 interface + 1 class = abstractness 0.5");
    }

    @Test
    void abstractClassCountsAsAbstractType() throws IOException {
        Path f = tempDir.resolve("Base.java");
        Files.writeString(f, """
                package dev.example.domain;
                public abstract class Base {
                    public abstract void handle();
                }
                """);

        ResolvedData data = resolver.resolve(Set.of(f));

        double abstractness = data.moduleAbstractness().getOrDefault(domain, 0.0);
        assertEquals(1.0, abstractness, 0.001, "Abstract class should count as abstract type");
    }
}
