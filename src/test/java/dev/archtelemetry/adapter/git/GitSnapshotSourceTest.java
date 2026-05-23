package dev.archtelemetry.adapter.git;

import dev.archtelemetry.adapter.java.JavaDependencyResolver;
import dev.archtelemetry.application.port.DependencyResolver;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitSnapshotSourceTest {

    @TempDir
    Path tempDir;

    private final Module domain = new Module("domain", List.of("dev.example.domain.**"));
    private final Module application = new Module("application", List.of("dev.example.application.**"));
    private final DependencyResolver resolver = new JavaDependencyResolver(Set.of(domain, application));

    private Git initRepo() throws Exception {
        return Git.init().setDirectory(tempDir.toFile()).call();
    }

    private String commit(Git git, String filename, String content) throws Exception {
        Path file = tempDir.resolve(filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.add().addFilepattern(".").call();
        RevCommit c = git.commit()
                .setMessage("commit")
                .setAuthor("test", "test@test.com")
                .setCommitter("test", "test@test.com")
                .call();
        return c.getId().getName();
    }

    @Test
    void singleCommitProducesOneSnapshotWithCorrectDependencies() throws Exception {
        try (Git git = initRepo()) {
            commit(git, "App.java", """
                    package dev.example.application;
                    import dev.example.domain.Foo;
                    class App {}
                    """);

            List<Snapshot> snapshots = new GitSnapshotSource(
                    tempDir, resolver, new SnapshotConfig.LastN(10)
            ).fetchSnapshots();

            assertEquals(1, snapshots.size());
            assertEquals(Set.of(new Dependency(application, domain)), snapshots.get(0).dependencies());
        }
    }

    @Test
    void multipleCommitsProduceOrderedSnapshotsOldestFirst() throws Exception {
        try (Git git = initRepo()) {
            String first = commit(git, "App.java", "package dev.example.application; class App {}");
            String second = commit(git, "App.java", """
                    package dev.example.application;
                    import dev.example.domain.Foo;
                    class App {}
                    """);

            List<Snapshot> snapshots = new GitSnapshotSource(
                    tempDir, resolver, new SnapshotConfig.LastN(10)
            ).fetchSnapshots();

            assertEquals(2, snapshots.size());
            assertEquals(first, snapshots.get(0).commitId());
            assertEquals(second, snapshots.get(1).commitId());
        }
    }

    @Test
    void laterCommitAddingDependencyShowsItInLaterSnapshot() throws Exception {
        try (Git git = initRepo()) {
            commit(git, "App.java", """
                    package dev.example.application;
                    class App {}
                    """);
            commit(git, "App.java", """
                    package dev.example.application;
                    import dev.example.domain.Foo;
                    class App {}
                    """);

            List<Snapshot> snapshots = new GitSnapshotSource(
                    tempDir, resolver, new SnapshotConfig.LastN(10)
            ).fetchSnapshots();

            assertEquals(2, snapshots.size());
            assertTrue(snapshots.get(0).dependencies().isEmpty());
            assertEquals(Set.of(new Dependency(application, domain)), snapshots.get(1).dependencies());
        }
    }

    @Test
    void laterCommitRemovingDependencyShowsItAbsentInLaterSnapshot() throws Exception {
        try (Git git = initRepo()) {
            commit(git, "App.java", """
                    package dev.example.application;
                    import dev.example.domain.Foo;
                    class App {}
                    """);
            commit(git, "App.java", """
                    package dev.example.application;
                    class App {}
                    """);

            List<Snapshot> snapshots = new GitSnapshotSource(
                    tempDir, resolver, new SnapshotConfig.LastN(10)
            ).fetchSnapshots();

            assertEquals(2, snapshots.size());
            assertEquals(Set.of(new Dependency(application, domain)), snapshots.get(0).dependencies());
            assertTrue(snapshots.get(1).dependencies().isEmpty());
        }
    }
}
