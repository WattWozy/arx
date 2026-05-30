package dev.archtelemetry.domain;

public enum LayerKind {
    // Layer 0 — innermost; pure business objects and rules, no dependencies on outer rings
    DOMAIN,
    // Layer 1 — interfaces/ports that the application exposes to adapters
    PORT,
    // Layer 2 — use-case orchestration; depends inward on DOMAIN and PORT
    APPLICATION,
    // Layer 3+ — I/O adapters (CLI, HTTP, DB, Git, …); depends inward on APPLICATION/PORT
    ADAPTER,
    // Third-party libraries not owned by this codebase
    EXTERNAL,
    // Could not be classified (test modules, anonymous modules, etc.)
    UNKNOWN;

    public static LayerKind resolve(Module module) {
        int layer = module.layer();
        if (layer >= 0) {
            return switch (layer) {
                case 0  -> DOMAIN;
                case 1  -> PORT;
                case 2  -> APPLICATION;
                default -> ADAPTER;
            };
        }

        String name = module.name();

        if (name.equals("domain") || name.startsWith("domain-")) return DOMAIN;
        if (name.contains("port")) return PORT;
        if (name.equals("application") || (name.startsWith("application-") && !name.contains("port")))
            return APPLICATION;
        if (name.startsWith("adapter-")) return ADAPTER;
        if (name.equals("test") || name.startsWith("test-")) return UNKNOWN;

        for (String pattern : module.packagePatterns()) {
            if (isExternalPrefix(pattern)) return EXTERNAL;
        }

        return UNKNOWN;
    }

    private static boolean isExternalPrefix(String pattern) {
        return pattern.startsWith("org.")
                || pattern.startsWith("com.sun.")
                || pattern.startsWith("javax.")
                || pattern.startsWith("java.")
                || pattern.startsWith("net.")
                || pattern.startsWith("io.")
                || pattern.startsWith("com.google.")
                || pattern.startsWith("com.fasterxml.");
    }
}
