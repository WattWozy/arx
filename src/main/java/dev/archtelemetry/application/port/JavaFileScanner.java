package dev.archtelemetry.application.port;

import java.nio.file.Path;
import java.util.Set;

@FunctionalInterface
public interface JavaFileScanner {
    Set<Path> scan(Path rootDir);
}
