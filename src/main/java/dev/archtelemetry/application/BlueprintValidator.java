package dev.archtelemetry.application;

import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.StaleModuleWarning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BlueprintValidator {

    public List<StaleModuleWarning> validate(Blueprint blueprint, Snapshot snapshot) {
        List<StaleModuleWarning> warnings = new ArrayList<>();
        for (Module module : blueprint.modules()) {
            boolean hasFiles = snapshot.moduleWmc().containsKey(module);
            boolean isSource = snapshot.dependencies().stream()
                    .anyMatch(d -> d.source().equals(module));
            if (!hasFiles && !isSource) {
                warnings.add(new StaleModuleWarning(module));
            }
        }
        return Collections.unmodifiableList(warnings);
    }
}
