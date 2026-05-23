package dev.archtelemetry.application.port;

import dev.archtelemetry.domain.CommitEntry;

import java.util.List;

public interface HistorySource {
    List<CommitEntry> fetchHistory();
}
