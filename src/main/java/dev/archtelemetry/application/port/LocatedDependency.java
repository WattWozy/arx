package dev.archtelemetry.application.port;

import dev.archtelemetry.domain.Dependency;

import java.nio.file.Path;

public record LocatedDependency(
        Dependency dependency,
        Path sourceFile,
        int lineNumber,
        String importText
) {}
