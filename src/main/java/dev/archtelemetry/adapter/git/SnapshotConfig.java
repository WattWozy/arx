package dev.archtelemetry.adapter.git;

import java.time.Instant;

public sealed interface SnapshotConfig {
    record LastN(int n) implements SnapshotConfig {}
    record DateRange(Instant from, Instant to) implements SnapshotConfig {}
    record All() implements SnapshotConfig {}
}
