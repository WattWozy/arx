package dev.archtelemetry.adapter.git;

import dev.archtelemetry.application.port.HistorySource;
import dev.archtelemetry.domain.CommitEntry;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GitHistorySource implements HistorySource {

    private final Path gitRoot;
    private final String scopePath; // forward-slash relative path from gitRoot to scanRoot, or null
    private final SnapshotConfig config;

    /** Backward-compatible: scan root equals git root. */
    public GitHistorySource(Path repoPath, SnapshotConfig config) {
        this(repoPath, repoPath, config);
    }

    public GitHistorySource(Path gitRoot, Path scanRoot, SnapshotConfig config) {
        this.gitRoot = gitRoot;
        String rel = gitRoot.relativize(scanRoot).toString().replace('\\', '/');
        this.scopePath = rel.isEmpty() ? null : rel;
        this.config = config;
    }

    @Override
    public List<CommitEntry> fetchHistory() {
        try (Git git = Git.open(gitRoot.toFile())) {
            Repository repo = git.getRepository();
            List<RevCommit> commits = collectCommits(repo);
            List<CommitEntry> entries = new ArrayList<>();

            try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(repo);
                for (RevCommit commit : commits) {
                    Set<String> changedPaths = getChangedPaths(repo, commit, df);
                    Instant ts = Instant.ofEpochSecond(commit.getCommitTime());
                    String email = commit.getAuthorIdent().getEmailAddress();
                    entries.add(new CommitEntry(commit.getId().getName(), email, ts, changedPaths));
                }
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<RevCommit> collectCommits(Repository repo) throws IOException {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            if (scopePath != null) {
                walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(scopePath), TreeFilter.ANY_DIFF));
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
        return commits;
    }

    private Set<String> getChangedPaths(Repository repo, RevCommit commit, DiffFormatter df) throws IOException {
        Set<String> paths = new HashSet<>();
        AbstractTreeIterator newTree = treeIterator(repo, commit);
        AbstractTreeIterator oldTree;

        if (commit.getParentCount() == 0) {
            oldTree = new EmptyTreeIterator();
        } else {
            try (RevWalk rw = new RevWalk(repo)) {
                RevCommit parent = rw.parseCommit(commit.getParent(0).getId());
                oldTree = treeIterator(repo, parent);
            }
        }

        String scopePrefix = scopePath != null ? scopePath + "/" : null;
        List<DiffEntry> diffs = df.scan(oldTree, newTree);
        for (DiffEntry diff : diffs) {
            String newPath = diff.getNewPath();
            if (!newPath.equals(DiffEntry.DEV_NULL)) {
                if (scopePrefix == null || newPath.startsWith(scopePrefix)) {
                    paths.add(scopePrefix != null ? newPath.substring(scopePrefix.length()) : newPath);
                }
            }
        }
        return paths;
    }

    private AbstractTreeIterator treeIterator(Repository repo, RevCommit commit) throws IOException {
        try (RevWalk rw = new RevWalk(repo)) {
            RevTree tree = rw.parseTree(commit.getTree().getId());
            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (ObjectReader reader = repo.newObjectReader()) {
                parser.reset(reader, tree.getId());
            }
            return parser;
        }
    }
}
