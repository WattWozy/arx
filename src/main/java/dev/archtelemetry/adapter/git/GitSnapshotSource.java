package dev.archtelemetry.adapter.git;

import dev.archtelemetry.application.port.DependencyResolver;
import dev.archtelemetry.application.port.ResolvedData;
import dev.archtelemetry.application.port.SnapshotSource;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class GitSnapshotSource implements SnapshotSource {

    private final Path gitRoot;
    private final String scopePath; // forward-slash relative path from gitRoot to scanRoot, or null
    private final DependencyResolver javaResolver;
    private final Function<Path, DependencyResolver> tsResolverFactory;
    private final Language language;
    private final SnapshotConfig config;

    /** Backward-compatible: scan root equals git root. */
    public GitSnapshotSource(Path repoPath, DependencyResolver resolver, SnapshotConfig config) {
        this(repoPath, repoPath, resolver, null, Language.JAVA, config);
    }

    /** Backward-compatible: scan root equals git root. */
    public GitSnapshotSource(Path repoPath, DependencyResolver javaResolver,
                              Function<Path, DependencyResolver> tsResolverFactory,
                              Language language, SnapshotConfig config) {
        this(repoPath, repoPath, javaResolver, tsResolverFactory, language, config);
    }

    public GitSnapshotSource(Path gitRoot, Path scanRoot, DependencyResolver javaResolver,
                              Function<Path, DependencyResolver> tsResolverFactory,
                              Language language, SnapshotConfig config) {
        this.gitRoot = gitRoot;
        String rel = gitRoot.relativize(scanRoot).toString().replace('\\', '/');
        this.scopePath = rel.isEmpty() ? null : rel;
        this.javaResolver = javaResolver;
        this.tsResolverFactory = tsResolverFactory;
        this.language = language;
        this.config = config;
    }

    @Override
    public List<Snapshot> fetchSnapshots() {
        try (Git git = Git.open(gitRoot.toFile())) {
            Repository repo = git.getRepository();
            List<RevCommit> commits = collectCommits(repo);
            List<Snapshot> snapshots = new ArrayList<>();
            for (RevCommit commit : commits) {
                snapshots.add(snapshotFor(repo, commit));
            }
            return List.copyOf(snapshots);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<RevCommit> collectCommits(Repository repo) throws IOException {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            if (config instanceof SnapshotConfig.DateRange) {
                walk.sort(RevSort.COMMIT_TIME_DESC);
            }
            walk.markStart(walk.parseCommit(repo.resolve("HEAD")));
            switch (config) {
                case SnapshotConfig.LastN lastN -> {
                    int count = 0;
                    for (RevCommit c : walk) {
                        if (count++ >= lastN.n()) break;
                        commits.add(c);
                    }
                }
                case SnapshotConfig.DateRange range -> {
                    for (RevCommit c : walk) {
                        Instant ts = Instant.ofEpochSecond(c.getCommitTime());
                        if (ts.isBefore(range.from())) break;
                        if (!ts.isAfter(range.to())) commits.add(c);
                    }
                }
                case SnapshotConfig.All all -> {
                    for (RevCommit c : walk) commits.add(c);
                }
            }
        }
        Collections.reverse(commits);
        return commits;
    }

    private Snapshot snapshotFor(Repository repo, RevCommit commit) throws IOException {
        Path tempDir = Files.createTempDirectory("archtelemetry-");
        try {
            ResolvedData resolved = switch (language) {
                case JAVA -> {
                    Set<Path> files = extractFiles(repo, commit, tempDir, ".java");
                    yield javaResolver.resolve(files);
                }
                case TYPESCRIPT -> {
                    Set<Path> files = extractFiles(repo, commit, tempDir, ".ts", ".tsx");
                    yield tsResolverFactory.apply(tempDir).resolve(files);
                }
                case AUTO -> {
                    Set<Path> javaFiles = extractFiles(repo, commit, tempDir, ".java");
                    Set<Path> tsFiles = extractFiles(repo, commit, tempDir, ".ts", ".tsx");
                    ResolvedData javaData = javaResolver.resolve(javaFiles);
                    ResolvedData tsData = tsResolverFactory.apply(tempDir).resolve(tsFiles);
                    yield mergeData(javaData, tsData);
                }
            };
            Instant ts = Instant.ofEpochSecond(commit.getCommitTime());
            return new Snapshot(commit.getId().getName(), ts, resolved.dependencies(),
                    resolved.moduleWmc(), resolved.moduleAbstractness());
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private Set<Path> extractFiles(Repository repo, RevCommit commit, Path tempDir,
                                    String... extensions) throws IOException {
        Set<Path> files = new HashSet<>();
        String scopePrefix = scopePath != null ? scopePath + "/" : null;
        for (String ext : extensions) {
            TreeFilter filter = scopePath != null
                    ? AndTreeFilter.create(PathFilter.create(scopePath), PathSuffixFilter.create(ext))
                    : PathSuffixFilter.create(ext);
            try (TreeWalk treeWalk = new TreeWalk(repo)) {
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                treeWalk.setFilter(filter);
                while (treeWalk.next()) {
                    String gitPath = treeWalk.getPathString();
                    // Strip scope prefix so tempDir mirrors scan-root-relative layout
                    String relPath = scopePrefix != null ? gitPath.substring(scopePrefix.length()) : gitPath;
                    ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
                    Path target = resolvePath(tempDir, relPath);
                    Files.createDirectories(target.getParent());
                    Files.write(target, loader.getBytes());
                    files.add(target);
                }
            }
        }
        return files;
    }

    private static ResolvedData mergeData(ResolvedData a, ResolvedData b) {
        Set<Dependency> deps = new HashSet<>(a.dependencies());
        deps.addAll(b.dependencies());
        Map<Module, Integer> wmc = new HashMap<>(a.moduleWmc());
        b.moduleWmc().forEach((m, c) -> wmc.merge(m, c, Integer::sum));
        Map<Module, Double> abstractness = new HashMap<>(a.moduleAbstractness());
        abstractness.putAll(b.moduleAbstractness());
        return new ResolvedData(Set.copyOf(deps), Map.copyOf(wmc), Map.copyOf(abstractness));
    }

    private Path resolvePath(Path base, String gitPath) {
        Path result = base;
        for (String part : gitPath.split("/")) {
            result = result.resolve(part);
        }
        return result;
    }

    private void deleteRecursive(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {
        }
    }
}
