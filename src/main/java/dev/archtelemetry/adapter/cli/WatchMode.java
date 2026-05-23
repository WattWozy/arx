package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.application.port.LocatingDependencyResolver;
import dev.archtelemetry.application.port.ResolvedDataWithLocations;
import dev.archtelemetry.application.AnalyzeIncremental;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.IncrementalResult;
import dev.archtelemetry.application.port.ResolvedData;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Snapshot;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

public final class WatchMode {

    private final Path sourceDir;
    private final Blueprint blueprint;
    private final LocatingDependencyResolver resolver;
    private final String fileExtension;
    private final boolean aiFeedback;

    public WatchMode(Path sourceDir, Blueprint blueprint, LocatingDependencyResolver resolver,
                     String fileExtension, boolean aiFeedback) {
        this.sourceDir = sourceDir;
        this.blueprint = blueprint;
        this.resolver = resolver;
        this.fileExtension = fileExtension;
        this.aiFeedback = aiFeedback;
    }

    public void run() throws IOException, InterruptedException {
        AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
        AnalyzeIncremental analyzeIncremental = new AnalyzeIncremental(resolver, analyzeSnapshot);

        Set<Path> allFiles = WorkingTreeScanner.scanFiles(sourceDir, fileExtension);
        ResolvedData initial = resolver.resolve(allFiles);
        Snapshot current = new Snapshot("watch-init", Instant.now(), initial.dependencies(), initial.moduleWmc());

        System.err.println("[arx] Watching " + sourceDir
                + " (" + allFiles.size() + " " + fileExtension + " files, "
                + current.dependencies().size() + " dependencies)");
        if (aiFeedback) {
            System.err.println("[arx] Output: ai-feedback JSON (stdout)");
        }

        WatchService watcher = FileSystems.getDefault().newWatchService();
        Map<WatchKey, Path> keyDirMap = new HashMap<>();
        registerAll(sourceDir, watcher, keyDirMap);

        while (true) {
            WatchKey key = watcher.take();
            Path watchedDir = keyDirMap.get(key);
            if (watchedDir == null) {
                key.reset();
                continue;
            }

            Set<Path> modified = new HashSet<>();
            Set<Path> deleted = new HashSet<>();

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                Path fullPath = watchedDir.resolve(((WatchEvent<Path>) event).context());

                if (kind == ENTRY_CREATE && Files.isDirectory(fullPath)) {
                    registerAll(fullPath, watcher, keyDirMap);
                } else if (fullPath.toString().endsWith(fileExtension)) {
                    if (kind == ENTRY_DELETE) {
                        deleted.add(fullPath);
                    } else {
                        modified.add(fullPath);
                    }
                }
            }
            key.reset();

            if (modified.isEmpty() && deleted.isEmpty()) continue;

            // Deletions require full rescan to purge removed modules from graph
            if (!deleted.isEmpty()) {
                Set<Path> remaining = WorkingTreeScanner.scanFiles(sourceDir, fileExtension);
                ResolvedData fullData = resolver.resolve(remaining);
                current = new Snapshot("watch-rescan", Instant.now(), fullData.dependencies(), fullData.moduleWmc());
                System.err.println("[arx] " + Instant.now() + " — "
                        + deleted.size() + " file(s) deleted, rescanned");
                if (aiFeedback) {
                    AnalyzeSnapshot snap = new AnalyzeSnapshot();
                    Set<dev.archtelemetry.domain.Violation> allViolations = snap.analyze(blueprint, current);
                    System.out.println(AiFeedbackWriter.generate(allViolations, blueprint));
                    System.out.flush();
                }
                modified.clear();
            }

            if (!modified.isEmpty()) {
                ResolvedDataWithLocations located = resolver.resolveWithLocations(modified);
                IncrementalResult result = analyzeIncremental.analyze(modified, blueprint, current);
                current = result.updatedSnapshot();

                System.err.println("[arx] " + Instant.now() + " — "
                        + modified.size() + " file(s) changed, "
                        + result.newViolations().size() + " new violation(s)");

                if (aiFeedback) {
                    System.out.println(AiFeedbackWriter.generate(
                            result.newViolations(), located.locatedDependencies(), blueprint));
                    System.out.flush();
                } else {
                    if (result.newViolations().isEmpty()) {
                        System.out.println("[OK] No new violations");
                    } else {
                        System.out.println("[VIOLATIONS] " + result.newViolations().size() + " new:");
                        result.newViolations().forEach(v ->
                                System.out.println("  " + v.dependency().source().name()
                                        + " -> " + v.dependency().target().name()));
                    }
                    System.out.flush();
                }
            }
        }
    }

    private static void registerAll(Path start, WatchService watcher, Map<WatchKey, Path> keyDirMap)
            throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey k = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                keyDirMap.put(k, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
