package dev.archtelemetry;

import dev.archtelemetry.adapter.coverage.JacocoXmlCoverageSource;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.MethodCoverage;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Snapshot;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CrapScoreTest {

    @Test
    void crapFormula_zeroCoverage() {
        // cc=2, coverage=0 -> crap = 4*(1)^3 + 2 = 6
        MethodCoverage mc = new MethodCoverage("com.example.Foo#bar", 2, 0.0);
        assertEquals(6.0, mc.crap(), 1e-9);
    }

    @Test
    void crapFormula_fullCoverage() {
        // cc=2, coverage=1 -> crap = 4*0 + 2 = 2
        MethodCoverage mc = new MethodCoverage("com.example.Foo#bar", 2, 1.0);
        assertEquals(2.0, mc.crap(), 1e-9);
    }

    @Test
    void crapFormula_partialCoverage() {
        // cc=1, coverage=0.5 -> crap = 1*(0.5)^3 + 1 = 0.125 + 1 = 1.125
        MethodCoverage mc = new MethodCoverage("com.example.Foo#bar", 1, 0.5);
        assertEquals(1.125, mc.crap(), 1e-9);
    }

    @Test
    void jacocoXmlParsesFixture() throws Exception {
        URL resource = getClass().getClassLoader().getResource("fixtures/jacoco.xml");
        assertNotNull(resource, "fixtures/jacoco.xml not found on classpath");
        Path xmlPath = Path.of(resource.toURI());

        JacocoXmlCoverageSource source = new JacocoXmlCoverageSource(xmlPath);
        List<MethodCoverage> coverage = source.fetchCoverage();

        assertFalse(coverage.isEmpty(), "Should parse at least one method");
        // Full-coverage method: compute in ModuleMetrics
        MethodCoverage computeMethod = coverage.stream()
                .filter(m -> m.fqn().contains("ModuleMetrics#compute"))
                .findFirst()
                .orElse(null);
        assertNotNull(computeMethod);
        assertEquals(1.0, computeMethod.lineCoverage(), 1e-9);
        assertEquals(2, computeMethod.cyclomaticComplexity());

        // Zero-coverage method: withCoverage
        MethodCoverage withCoverageMethod = coverage.stream()
                .filter(m -> m.fqn().contains("ModuleMetrics#withCoverage"))
                .findFirst()
                .orElse(null);
        assertNotNull(withCoverageMethod);
        assertEquals(0.0, withCoverageMethod.lineCoverage(), 1e-9);
    }

    @Test
    void computeMetricsWithCoveragePopulatesTestDebtScore() throws Exception {
        Module domain = new Module("domain", List.of("dev.archtelemetry.domain.**"), 0);
        Module app = new Module("application", List.of("dev.archtelemetry.application.**"), 1);
        Blueprint blueprint = new Blueprint(Set.of(domain, app), Set.of());
        Snapshot snapshot = new Snapshot("c1", Instant.now(), Set.of(), Map.of());

        URL resource = getClass().getClassLoader().getResource("fixtures/jacoco.xml");
        assertNotNull(resource);
        Path xmlPath = Path.of(resource.toURI());
        JacocoXmlCoverageSource coverageSource = new JacocoXmlCoverageSource(xmlPath);

        ComputeMetrics computeMetrics = new ComputeMetrics(new AnalyzeSnapshot());
        var profile = computeMetrics.compute(blueprint, snapshot, Map.of(), coverageSource);

        ModuleMetrics domainMetrics = profile.moduleMetrics().stream()
                .filter(m -> m.module().equals(domain)).findFirst().orElseThrow();
        ModuleMetrics appMetrics = profile.moduleMetrics().stream()
                .filter(m -> m.module().equals(app)).findFirst().orElseThrow();

        // domain has 3 methods in fixture: 2 in ModuleMetrics + 1 in MethodCoverage
        assertTrue(domainMetrics.crapScore() > 0, "domain should have nonzero CRAP score");
        assertTrue(appMetrics.crapScore() > 0, "application should have nonzero CRAP score");
        // WithCoverage method has 0% coverage -> undercovered -> testDebt > 0
        assertTrue(domainMetrics.testDebtScore() > 0, "domain should have nonzero test debt");
    }
}
