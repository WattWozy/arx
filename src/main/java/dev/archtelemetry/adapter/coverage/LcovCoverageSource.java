package dev.archtelemetry.adapter.coverage;

import dev.archtelemetry.application.port.CoverageSource;
import dev.archtelemetry.domain.MethodCoverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LcovCoverageSource implements CoverageSource {

    private final Path reportPath;

    public LcovCoverageSource(Path reportPath) {
        this.reportPath = reportPath;
    }

    @Override
    public List<MethodCoverage> fetchCoverage() {
        try {
            List<String> lines = Files.readAllLines(reportPath);
            List<MethodCoverage> result = new ArrayList<>();

            String currentFile = "";
            // methodName -> hitCount
            Map<String, Long> hitCounts = new HashMap<>();

            for (String line : lines) {
                if (line.startsWith("SF:")) {
                    currentFile = line.substring(3).strip();
                    hitCounts.clear();
                } else if (line.startsWith("FNDA:")) {
                    String rest = line.substring(5);
                    int comma = rest.indexOf(',');
                    if (comma > 0) {
                        long hits = parseLong(rest.substring(0, comma).strip());
                        String name = rest.substring(comma + 1).strip();
                        hitCounts.merge(name, hits, Long::sum);
                    }
                } else if (line.equals("end_of_record")) {
                    String classFqn = filePathToFqn(currentFile);
                    for (Map.Entry<String, Long> entry : hitCounts.entrySet()) {
                        String fqn = classFqn + "#" + entry.getKey();
                        double coverage = entry.getValue() > 0 ? 1.0 : 0.0;
                        // lcov does not provide CC; default to 1
                        result.add(new MethodCoverage(fqn, 1, coverage));
                    }
                    hitCounts.clear();
                }
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String filePathToFqn(String filePath) {
        // src/main/java/com/example/Foo.java -> com.example.Foo
        String path = filePath.replace('\\', '/');
        String marker = "src/main/java/";
        int idx = path.indexOf(marker);
        if (idx >= 0) path = path.substring(idx + marker.length());
        if (path.endsWith(".java")) path = path.substring(0, path.length() - 5);
        return path.replace('/', '.');
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }
}
