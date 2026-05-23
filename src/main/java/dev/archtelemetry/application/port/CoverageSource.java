package dev.archtelemetry.application.port;

import dev.archtelemetry.domain.MethodCoverage;
import java.util.List;

@FunctionalInterface
public interface CoverageSource {
    List<MethodCoverage> fetchCoverage();
}
