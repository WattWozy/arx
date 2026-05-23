package dev.archtelemetry.application.port;

import java.nio.file.Path;
import java.util.Set;

public interface DependencyResolver {
    ResolvedData resolve(Set<Path> sourceFiles);
}
