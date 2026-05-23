package dev.archtelemetry.application;

import dev.archtelemetry.application.port.CoverageSource;
import dev.archtelemetry.domain.ArchitectureCommunity;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.MethodCoverage;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleGitStats;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.RefactoringSuggestion;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Violation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ComputeMetrics {

    private final AnalyzeSnapshot analyzeSnapshot;

    public ComputeMetrics(AnalyzeSnapshot analyzeSnapshot) {
        this.analyzeSnapshot = analyzeSnapshot;
    }

    public ArchitectureProfile compute(Blueprint blueprint, Snapshot snapshot) {
        return compute(blueprint, snapshot, Map.of(), null);
    }

    public ArchitectureProfile compute(Blueprint blueprint, Snapshot snapshot, Map<Module, ModuleGitStats> gitStats) {
        return compute(blueprint, snapshot, gitStats, null);
    }

    public ArchitectureProfile compute(Blueprint blueprint, Snapshot snapshot,
                                       Map<Module, ModuleGitStats> gitStats, CoverageSource coverageSource) {
        Set<Dependency> deps = snapshot.dependencies();
        Set<Module> modules = blueprint.modules();
        Map<Module, Integer> wmcByModule = snapshot.moduleWmc();
        Map<Module, Double> abstractnessByModule = snapshot.moduleAbstractness();

        Map<Module, ModuleMetrics> metricsMap = new HashMap<>();
        for (Module m : modules) {
            int fanOut = 0;
            int fanIn = 0;
            for (Dependency dep : deps) {
                if (dep.source().equals(m)) fanOut++;
                if (dep.target().equals(m)) fanIn++;
            }
            int wmc = wmcByModule.getOrDefault(m, 0);
            double abstractness = abstractnessByModule.getOrDefault(m, 0.0);
            metricsMap.put(m, ModuleMetrics.compute(m, fanIn, fanOut, wmc, gitStats.get(m), abstractness));
        }

        // Graph metrics: PageRank + betweenness
        Map<Module, double[]> graphMetrics = computeGraphMetrics(new ArrayList<>(modules), deps);
        for (Map.Entry<Module, double[]> e : graphMetrics.entrySet()) {
            ModuleMetrics mm = metricsMap.get(e.getKey());
            if (mm != null) {
                double pr = e.getValue()[0];
                double bet = e.getValue()[1];
                double hub = pr * bet * Math.max(1, mm.wmc());
                metricsMap.put(e.getKey(), mm.withGraphMetrics(pr, bet, hub));
            }
        }

        // Coverage metrics: CRAP + testDebt
        if (coverageSource != null) {
            List<MethodCoverage> coverage = coverageSource.fetchCoverage();
            Map<Module, Double> totalCrap = new HashMap<>();
            Map<Module, long[]> methodCounts = new HashMap<>(); // [measured, undercovered]
            for (MethodCoverage mc : coverage) {
                Module module = resolveModule(modules, mc.fqn());
                if (module == null) continue;
                double crap = mc.crap();
                totalCrap.merge(module, crap, Double::sum);
                long[] counts = methodCounts.computeIfAbsent(module, k -> new long[]{0, 0});
                counts[0]++;
                if (mc.lineCoverage() < 1.0) counts[1]++;
            }
            for (Module m : modules) {
                ModuleMetrics mm = metricsMap.get(m);
                if (mm == null) continue;
                double crap = totalCrap.getOrDefault(m, 0.0);
                long[] counts = methodCounts.getOrDefault(m, new long[]{0, 0});
                double testDebt = counts[0] > 0 ? crap * ((double) counts[1] / counts[0]) : 0.0;
                metricsMap.put(m, mm.withCoverage(crap, testDebt));
            }
        }

        Set<ModuleMetrics> metricsSet = new HashSet<>(metricsMap.values());
        Set<DependencyCycle> cycles = detectCycles(new ArrayList<>(modules), deps);
        Set<Violation> violations = analyzeSnapshot.analyze(blueprint, snapshot);
        List<RefactoringSuggestion> suggestions = new SuggestRefactorings().suggest(metricsSet, deps);
        Set<ArchitectureCommunity> communities = detectCommunities(modules, deps);

        return new ArchitectureProfile(metricsSet, cycles, violations, suggestions, communities);
    }

    // -------------------------------------------------------------------------
    // PageRank + Betweenness (Brandes)
    // -------------------------------------------------------------------------

    private Map<Module, double[]> computeGraphMetrics(List<Module> modules, Set<Dependency> deps) {
        int n = modules.size();
        Map<Module, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(modules.get(i), i);

        // Adjacency lists
        List<List<Integer>> adj = new ArrayList<>();
        int[] outDeg = new int[n];
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (Dependency dep : deps) {
            Integer s = idx.get(dep.source());
            Integer t = idx.get(dep.target());
            if (s != null && t != null && !s.equals(t)) {
                adj.get(s).add(t);
                outDeg[s]++;
            }
        }

        double[] rank = pageRank(n, adj, outDeg);
        double[] betweenness = brandes(n, adj);

        Map<Module, double[]> result = new HashMap<>();
        for (int i = 0; i < n; i++) {
            result.put(modules.get(i), new double[]{rank[i], betweenness[i]});
        }
        return result;
    }

    private double[] pageRank(int n, List<List<Integer>> adj, int[] outDeg) {
        if (n == 0) return new double[0];
        double d = 0.85;
        double[] rank = new double[n];
        double[] newRank = new double[n];
        for (int i = 0; i < n; i++) rank[i] = 1.0 / n;

        for (int iter = 0; iter < 15; iter++) {
            double dangling = 0.0;
            for (int i = 0; i < n; i++) {
                if (outDeg[i] == 0) dangling += rank[i];
            }
            for (int v = 0; v < n; v++) {
                newRank[v] = (1.0 - d) / n + d * dangling / n;
            }
            // Distribute rank from nodes with outgoing edges
            for (int u = 0; u < n; u++) {
                if (outDeg[u] > 0) {
                    double share = d * rank[u] / outDeg[u];
                    for (int v : adj.get(u)) {
                        newRank[v] += share;
                    }
                }
            }
            double[] tmp = rank; rank = newRank; newRank = tmp;
            for (int i = 0; i < n; i++) newRank[i] = 0.0;
        }
        return rank;
    }

    private double[] brandes(int n, List<List<Integer>> adj) {
        double[] betweenness = new double[n];
        if (n <= 2) return betweenness;

        for (int s = 0; s < n; s++) {
            Deque<Integer> stack = new ArrayDeque<>();
            List<List<Integer>> pred = new ArrayList<>();
            for (int i = 0; i < n; i++) pred.add(new ArrayList<>());
            double[] sigma = new double[n];
            int[] dist = new int[n];
            sigma[s] = 1.0;
            java.util.Arrays.fill(dist, -1);
            dist[s] = 0;

            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(s);
            while (!queue.isEmpty()) {
                int v = queue.poll();
                stack.push(v);
                for (int w : adj.get(v)) {
                    if (dist[w] < 0) {
                        queue.add(w);
                        dist[w] = dist[v] + 1;
                    }
                    if (dist[w] == dist[v] + 1) {
                        sigma[w] += sigma[v];
                        pred.get(w).add(v);
                    }
                }
            }

            double[] delta = new double[n];
            while (!stack.isEmpty()) {
                int w = stack.pop();
                for (int v : pred.get(w)) {
                    delta[v] += (sigma[v] / sigma[w]) * (1.0 + delta[w]);
                }
                if (w != s) betweenness[w] += delta[w];
            }
        }
        return betweenness;
    }

    // -------------------------------------------------------------------------
    // Coverage: map method FQN to module by package pattern
    // -------------------------------------------------------------------------

    private Module resolveModule(Set<Module> modules, String fqn) {
        // FQN format: "com.example.ClassName#methodName"
        String className = fqn.contains("#") ? fqn.substring(0, fqn.indexOf('#')) : fqn;
        // className -> package (drop simple class name, keep package)
        int lastDot = className.lastIndexOf('.');
        String pkg = lastDot > 0 ? className.substring(0, lastDot) : className;
        for (Module m : modules) {
            for (String pattern : m.packagePatterns()) {
                if (packageMatchesPattern(pkg, pattern) || classMatchesPattern(className, pattern)) {
                    return m;
                }
            }
        }
        return null;
    }

    private boolean packageMatchesPattern(String pkg, String pattern) {
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return pkg.equals(prefix) || pkg.startsWith(prefix + ".");
        }
        return pkg.equals(pattern);
    }

    private boolean classMatchesPattern(String className, String pattern) {
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return className.startsWith(prefix + ".");
        }
        return className.startsWith(pattern + ".");
    }

    // -------------------------------------------------------------------------
    // Community detection (union-find)
    // -------------------------------------------------------------------------

    private Set<ArchitectureCommunity> detectCommunities(Set<Module> modules, Set<Dependency> deps) {
        Map<Module, Module> parent = new HashMap<>();
        for (Module m : modules) parent.put(m, m);

        for (Dependency dep : deps) {
            if (modules.contains(dep.source()) && modules.contains(dep.target())) {
                union(parent, dep.source(), dep.target());
            }
        }

        Map<Module, Set<Module>> groups = new HashMap<>();
        for (Module m : modules) {
            Module root = find(parent, m);
            groups.computeIfAbsent(root, k -> new HashSet<>()).add(m);
        }

        return groups.values().stream()
                .map(group -> new ArchitectureCommunity(group, communityName(group)))
                .collect(Collectors.toSet());
    }

    private Module find(Map<Module, Module> parent, Module m) {
        if (!parent.get(m).equals(m)) {
            parent.put(m, find(parent, parent.get(m)));
        }
        return parent.get(m);
    }

    private void union(Map<Module, Module> parent, Module a, Module b) {
        Module ra = find(parent, a);
        Module rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    private String communityName(Set<Module> modules) {
        return modules.stream()
                .map(Module::name)
                .sorted()
                .collect(Collectors.joining(", ", "cluster(", ")"));
    }

    // -------------------------------------------------------------------------
    // Cycle detection (Tarjan SCC)
    // -------------------------------------------------------------------------

    private Set<DependencyCycle> detectCycles(List<Module> modules, Set<Dependency> deps) {
        Map<Module, Set<Module>> adj = new HashMap<>();
        for (Module m : modules) adj.put(m, new HashSet<>());
        for (Dependency dep : deps) {
            if (adj.containsKey(dep.source()) && adj.containsKey(dep.target())) {
                adj.get(dep.source()).add(dep.target());
            }
        }

        Map<Module, Integer> index = new HashMap<>();
        Map<Module, Integer> lowlink = new HashMap<>();
        Map<Module, Boolean> onStack = new HashMap<>();
        Deque<Module> stack = new ArrayDeque<>();
        Set<DependencyCycle> cycles = new HashSet<>();
        int[] counter = {0};

        for (Module m : modules) {
            if (!index.containsKey(m)) {
                tarjan(m, adj, index, lowlink, onStack, stack, cycles, counter);
            }
        }
        return cycles;
    }

    private void tarjan(Module v, Map<Module, Set<Module>> adj,
                        Map<Module, Integer> index, Map<Module, Integer> lowlink,
                        Map<Module, Boolean> onStack, Deque<Module> stack,
                        Set<DependencyCycle> cycles, int[] counter) {
        index.put(v, counter[0]);
        lowlink.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.put(v, true);

        for (Module w : adj.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                tarjan(w, adj, index, lowlink, onStack, stack, cycles, counter);
                lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w)));
            } else if (Boolean.TRUE.equals(onStack.get(w))) {
                lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
            }
        }

        if (lowlink.get(v).equals(index.get(v))) {
            List<Module> scc = new ArrayList<>();
            Module w;
            do {
                w = stack.pop();
                onStack.put(w, false);
                scc.add(w);
            } while (!w.equals(v));
            if (scc.size() >= 2) {
                cycles.add(new DependencyCycle(scc));
            }
        }
    }
}
