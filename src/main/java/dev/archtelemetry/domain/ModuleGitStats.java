package dev.archtelemetry.domain;

import java.util.Set;

public record ModuleGitStats(
        Module module,
        int commitCount,
        int authorCount,
        Set<String> authorEmails,
        int commitsLast30d,
        double avgCommitsPer30dWindow
) {
    public ModuleGitStats {
        authorEmails = Set.copyOf(authorEmails);
    }

    public double churnAcceleration() {
        if (avgCommitsPer30dWindow == 0.0) {
            return commitsLast30d > 0 ? 2.0 : 1.0;
        }
        return commitsLast30d / avgCommitsPer30dWindow;
    }

    public double busFactorRisk() {
        return authorCount == 0 ? 0.0 : (double) commitCount / authorCount;
    }
}
