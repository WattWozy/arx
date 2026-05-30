package dev.archtelemetry.domain;

public enum EdgeKind {
    // source → a PORT module (correct hexagonal pattern for adapters)
    PORT_ACCESS,
    // ADAPTER → DOMAIN without going through a port (possible bypass)
    DOMAIN_ACCESS,
    // ADAPTER → ADAPTER (peer coupling violation)
    LATERAL_COUPLING,
    // Dependency flows inward (ADAPTER→APPLICATION, APPLICATION→DOMAIN, PORT→DOMAIN, etc.)
    INWARD_FLOW,
    // Dependency flows outward, inverting hexagonal direction (DOMAIN→APPLICATION, APPLICATION→ADAPTER)
    OUTWARD_FLOW,
    // Dependency on a third-party library — always acceptable for adapters
    EXTERNAL_LIB,
    // Cannot be classified (e.g. one side is UNKNOWN layer)
    UNKNOWN;

    /**
     * Classifies a directed edge given the layer kinds of source and target.
     * Special cases are resolved first; the general case uses layer depth ordering:
     *   ADAPTER(3) > APPLICATION(2) > PORT(1) > DOMAIN(0)
     * so source.ordinal() > target.ordinal() means the dependency flows inward (correct).
     */
    public static EdgeKind classify(LayerKind source, LayerKind target) {
        if (target == LayerKind.EXTERNAL) return EXTERNAL_LIB;
        if (source == LayerKind.UNKNOWN || target == LayerKind.UNKNOWN) return UNKNOWN;
        if (source == LayerKind.EXTERNAL) return UNKNOWN;

        // Adapter-specific special cases
        if (source == LayerKind.ADAPTER && target == LayerKind.ADAPTER)      return LATERAL_COUPLING;
        if (source == LayerKind.ADAPTER && target == LayerKind.PORT)          return PORT_ACCESS;
        if (source == LayerKind.ADAPTER && target == LayerKind.DOMAIN)        return DOMAIN_ACCESS;

        // General rule: enum ordinal encodes layer depth (DOMAIN=0 … ADAPTER=3)
        int srcDepth = source.ordinal();
        int tgtDepth = target.ordinal();
        if (srcDepth > tgtDepth) return INWARD_FLOW;
        if (srcDepth < tgtDepth) return OUTWARD_FLOW;
        return LATERAL_COUPLING; // same layer (e.g. APPLICATION→APPLICATION)
    }
}
