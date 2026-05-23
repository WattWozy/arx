package dev.archtelemetry;

import dev.archtelemetry.adapter.cli.BlueprintLoader;
import dev.archtelemetry.application.InferBlueprint;
import dev.archtelemetry.domain.Blueprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InferBlueprintTest {

    private final InferBlueprint infer = new InferBlueprint();

    @Test
    void groupsPackagesByPrefixDepth() {
        Set<String> packages = Set.of(
                "dev.archtelemetry.domain",
                "dev.archtelemetry.application",
                "dev.archtelemetry.adapter.cli",
                "dev.archtelemetry.adapter.java"
        );
        String result = infer.infer(packages, List.of(), 2);

        assertTrue(result.contains("domain"), "Should create domain module");
        assertTrue(result.contains("application"), "Should create application module");
        assertTrue(result.contains("adapter-cli") || result.contains("adapter.cli"),
                "Should create adapter-cli module");
        assertTrue(result.contains("adapter-java") || result.contains("adapter.java"),
                "Should create adapter-java module");
    }

    @Test
    void depsBelowThresholdAreExcluded() {
        Set<String> packages = Set.of("com.example.a", "com.example.b");
        List<Map.Entry<String, String>> deps = List.of(
                Map.entry("com.example.a", "com.example.b")
        );
        // threshold=2: the single dep should be excluded
        String result = infer.infer(packages, deps, 1, 2);
        assertFalse(result.contains("allow"), "Dep below threshold should not appear");
    }

    @Test
    void depsAboveThresholdAreIncluded() {
        Set<String> packages = Set.of("com.example.a", "com.example.b");
        List<Map.Entry<String, String>> deps = List.of(
                Map.entry("com.example.a", "com.example.b"),
                Map.entry("com.example.a", "com.example.b")
        );
        // threshold=2: two observations of the same dep should include it
        String result = infer.infer(packages, deps, 1, 2);
        assertTrue(result.contains("allow"), "Dep meeting threshold should appear");
    }

    @Test
    void outputIsValidBlueprintLoadableByBlueprintLoader(@TempDir Path tmp) throws IOException {
        Set<String> packages = Set.of(
                "dev.archtelemetry.domain",
                "dev.archtelemetry.application"
        );
        List<Map.Entry<String, String>> deps = List.of(
                Map.entry("dev.archtelemetry.application", "dev.archtelemetry.domain")
        );

        String blueprintText = infer.infer(packages, deps, 2);
        Path blueprintFile = tmp.resolve("inferred.blueprint");
        Files.writeString(blueprintFile, blueprintText);

        Blueprint loaded = BlueprintLoader.load(blueprintFile);
        assertFalse(loaded.modules().isEmpty(), "Inferred blueprint should load with at least one module");
        assertEquals(2, loaded.modules().size(), "Should have domain and application modules");
    }

    @Test
    void emptyPackagesReturnsPlaceholder() {
        String result = infer.infer(Set.of(), List.of(), 2);
        assertTrue(result.contains("no packages"), "Empty input should produce a placeholder comment");
    }

    @Test
    void moduleNamesUseDashSeparator() {
        Set<String> packages = Set.of("com.example.adapter.rest");
        String result = infer.infer(packages, List.of(), 2);
        assertTrue(result.contains("adapter-rest"), "Module name should use dash not dot");
    }
}
