package dev.archtelemetry.application.port;

import java.util.List;

public record ResolvedDataWithLocations(
        ResolvedData data,
        List<LocatedDependency> locatedDependencies
) {}
