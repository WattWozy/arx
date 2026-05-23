package dev.archtelemetry.application.port;

import dev.archtelemetry.domain.Blueprint;

import java.nio.file.Path;

@FunctionalInterface
public interface BlueprintSource {
    Blueprint load(Path path);
}
