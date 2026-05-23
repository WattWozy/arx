package dev.archtelemetry.application.port;

import dev.archtelemetry.domain.Snapshot;

import java.util.List;

public interface SnapshotSource {
    List<Snapshot> fetchSnapshots();
}
