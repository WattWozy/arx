package dev.archtelemetry.application.port;

import java.nio.file.Path;
import java.util.Set;

public interface LocatingDependencyResolver extends DependencyResolver {
    ResolvedDataWithLocations resolveWithLocations(Set<Path> sourceFiles);
}
