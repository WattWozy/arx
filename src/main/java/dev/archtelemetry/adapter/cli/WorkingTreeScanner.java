package dev.archtelemetry.adapter.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

public final class WorkingTreeScanner {

    private WorkingTreeScanner() {}

    public static Set<Path> scanJavaFiles(Path rootDir) {
        return scanFiles(rootDir, ".java");
    }

    public static Set<Path> scanFiles(Path rootDir, String... extensions) {
        Set<Path> files = new HashSet<>();
        try {
            Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.toString();
                    for (String ext : extensions) {
                        if (name.endsWith(ext)) {
                            files.add(file);
                            break;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Set.copyOf(files);
    }
}
