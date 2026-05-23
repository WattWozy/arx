package dev.archtelemetry.domain;

public record ModuleMetrics(
        Module module,
        int fanIn,
        int fanOut,
        double instability,
        double abstractness,
        double distanceFromMainSequence,
        int wmc,
        double hotspot,
        double churnAcceleration,
        double busFactorRisk,
        double crapScore,
        double testDebtScore,
        double pageRank,
        double betweenness,
        double hubScore
) {
    public static ModuleMetrics compute(Module module, int fanIn, int fanOut) {
        return compute(module, fanIn, fanOut, 0, null, 0.0);
    }

    public static ModuleMetrics compute(Module module, int fanIn, int fanOut, int wmc, ModuleGitStats gitStats) {
        return compute(module, fanIn, fanOut, wmc, gitStats, 0.0);
    }

    public static ModuleMetrics compute(Module module, int fanIn, int fanOut, int wmc, ModuleGitStats gitStats,
                                        double abstractness) {
        double instability = (fanIn + fanOut) == 0 ? 0.0 : (double) fanOut / (fanIn + fanOut);
        double distance = Math.abs(abstractness + instability - 1.0);
        double hotspot = gitStats != null ? (double) wmc * gitStats.commitCount() : 0.0;
        double churnAcceleration = gitStats != null ? gitStats.churnAcceleration() : 0.0;
        double busFactorRisk = gitStats != null ? gitStats.busFactorRisk() : 0.0;
        return new ModuleMetrics(module, fanIn, fanOut, instability, abstractness, distance,
                wmc, hotspot, churnAcceleration, busFactorRisk,
                0.0, 0.0, 0.0, 0.0, 0.0);
    }

    public ModuleMetrics withCoverage(double crapScore, double testDebtScore) {
        return new ModuleMetrics(module, fanIn, fanOut, instability, abstractness, distanceFromMainSequence,
                wmc, hotspot, churnAcceleration, busFactorRisk,
                crapScore, testDebtScore, pageRank, betweenness, hubScore);
    }

    public ModuleMetrics withGraphMetrics(double pageRank, double betweenness, double hubScore) {
        return new ModuleMetrics(module, fanIn, fanOut, instability, abstractness, distanceFromMainSequence,
                wmc, hotspot, churnAcceleration, busFactorRisk,
                crapScore, testDebtScore, pageRank, betweenness, hubScore);
    }
}
