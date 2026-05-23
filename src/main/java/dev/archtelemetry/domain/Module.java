package dev.archtelemetry.domain;

import java.util.List;
import java.util.Objects;

public final class Module {

    private final String name;
    private final List<String> packagePatterns;
    private final int layer;

    public Module(String name) {
        this(name, List.of(), -1);
    }

    public Module(String name, List<String> packagePatterns) {
        this(name, packagePatterns, -1);
    }

    public Module(String name, List<String> packagePatterns, int layer) {
        this.name = Objects.requireNonNull(name);
        this.packagePatterns = List.copyOf(packagePatterns);
        this.layer = layer;
    }

    public String name() {
        return name;
    }

    public List<String> packagePatterns() {
        return packagePatterns;
    }

    public int layer() {
        return layer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Module m)) return false;
        return name.equals(m.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "Module[name=" + name + "]";
    }
}
