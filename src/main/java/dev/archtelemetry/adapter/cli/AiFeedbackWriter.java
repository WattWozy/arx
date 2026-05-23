package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.application.port.LocatedDependency;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AiFeedbackWriter {

    private AiFeedbackWriter() {}

    public static String generate(Set<Violation> violations, List<LocatedDependency> locations, Blueprint blueprint) {
        StringBuilder sb = new StringBuilder("[\n");
        List<String> entries = new ArrayList<>();

        for (Violation v : violations) {
            List<LocatedDependency> matching = locations.stream()
                    .filter(l -> l.dependency().equals(v.dependency()))
                    .toList();

            if (matching.isEmpty()) {
                entries.add(formatEntry(v, null, blueprint));
            } else {
                for (LocatedDependency loc : matching) {
                    entries.add(formatEntry(v, loc, blueprint));
                }
            }
        }

        sb.append(String.join(",\n", entries));
        sb.append("\n]");
        return sb.toString();
    }

    public static String generate(Set<Violation> violations, Blueprint blueprint) {
        return generate(violations, List.of(), blueprint);
    }

    private static String formatEntry(Violation v, LocatedDependency loc, Blueprint blueprint) {
        String src = v.dependency().source().name();
        String tgt = v.dependency().target().name();
        String fix = determineFix(v, blueprint);

        StringBuilder sb = new StringBuilder("  {\n");
        sb.append("    \"sourceModule\": ").append(js(src)).append(",\n");
        sb.append("    \"targetModule\": ").append(js(tgt)).append(",\n");

        if (loc != null) {
            sb.append("    \"sourceFile\": ").append(js(loc.sourceFile().toString())).append(",\n");
            sb.append("    \"lineNumber\": ").append(loc.lineNumber()).append(",\n");
            sb.append("    \"importStatement\": ").append(js(loc.importText())).append(",\n");
        } else {
            sb.append("    \"sourceFile\": null,\n");
            sb.append("    \"lineNumber\": null,\n");
            sb.append("    \"importStatement\": null,\n");
        }

        sb.append("    \"violatedRule\": ").append(js("allow " + src + " -> " + tgt + " (missing)")).append(",\n");
        sb.append("    \"suggestedFix\": ").append(js(fix)).append("\n");
        sb.append("  }");
        return sb.toString();
    }

    private static String determineFix(Violation v, Blueprint blueprint) {
        String src = v.dependency().source().name();
        String tgt = v.dependency().target().name();
        int srcLayer = v.dependency().source().layer();
        int tgtLayer = v.dependency().target().layer();

        if (srcLayer >= 0 && tgtLayer >= 0 && srcLayer < tgtLayer) {
            return "Dependency inversion required: '" + src + "' (layer " + srcLayer + ", inner) must not import '"
                    + tgt + "' (layer " + tgtLayer + ", outer). "
                    + "Declare a port interface in '" + src + "' and implement it in '" + tgt + "'. "
                    + "The inner layer depends on the abstraction, not the outer implementation.";
        }
        return "Remove the import from '" + src + "' to '" + tgt + "', "
                + "or add 'allow " + src + " -> " + tgt + "' to your blueprint file. "
                + "If this dependency is intentional, add the allow rule; "
                + "if not, move the logic to an appropriate layer.";
    }

    private static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }
}
