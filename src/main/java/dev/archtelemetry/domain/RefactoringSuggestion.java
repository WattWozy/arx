package dev.archtelemetry.domain;

public record RefactoringSuggestion(Module module, Type type, String reason) {
    public enum Type { SPLIT, MERGE }
}
