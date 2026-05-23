package dev.archtelemetry.adapter.typescript;

import dev.archtelemetry.application.port.LocatedDependency;
import dev.archtelemetry.application.port.ResolvedDataWithLocations;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeScriptDependencyResolverTest {

    @TempDir
    Path tempDir;

    private final Module domain = new Module("domain", List.of("src/domain/**"), 0);
    private final Module application = new Module("application", List.of("src/application/**"), 1);
    private final Module infrastructure = new Module("infrastructure", List.of("src/infrastructure/**"), 2);

    private TypeScriptDependencyResolver resolver() {
        return new TypeScriptDependencyResolver(Set.of(domain, application, infrastructure), tempDir);
    }

    private Path file(String relativePath, String content) throws IOException {
        Path p = tempDir.resolve(relativePath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void esImportFromKnownModuleProducesCorrectDependency() throws IOException {
        Path f = file("src/application/AppService.ts", """
                import { Entity } from '../domain/Entity';
                export class AppService {}
                """);

        assertEquals(
                Set.of(new Dependency(application, domain)),
                resolver().resolve(Set.of(f)).dependencies()
        );
    }

    @Test
    void forbiddenImportFromDomainToInfraProducesCorrectDependency() throws IOException {
        Path f = file("src/domain/Entity.ts", """
                import { Repo } from '../infrastructure/Repo';
                export class Entity {}
                """);

        assertEquals(
                Set.of(new Dependency(domain, infrastructure)),
                resolver().resolve(Set.of(f)).dependencies()
        );
    }

    @Test
    void importsWithinSameModuleAreExcluded() throws IOException {
        Path f = file("src/domain/Entity.ts", """
                import { ValueObject } from './ValueObject';
                export class Entity {}
                """);

        assertTrue(resolver().resolve(Set.of(f)).dependencies().isEmpty());
    }

    @Test
    void nodeModulesImportsAreIgnored() throws IOException {
        Path f = file("src/application/Service.ts", """
                import express from 'express';
                import { Injectable } from '@nestjs/common';
                export class Service {}
                """);

        assertTrue(resolver().resolve(Set.of(f)).dependencies().isEmpty());
    }

    @Test
    void fileWithNoImportsProducesNoDependencies() throws IOException {
        Path f = file("src/domain/ValueObject.ts", """
                export abstract class ValueObject {}
                """);

        assertTrue(resolver().resolve(Set.of(f)).dependencies().isEmpty());
    }

    @Test
    void multipleFilesProduceAggregatedDependencySet() throws IOException {
        Path appFile = file("src/application/AppService.ts", """
                import { Entity } from '../domain/Entity';
                export class AppService {}
                """);
        Path infraFile = file("src/infrastructure/Repo.ts", """
                import { AppService } from '../application/AppService';
                export class Repo {}
                """);

        assertEquals(
                Set.of(
                        new Dependency(application, domain),
                        new Dependency(infrastructure, application)
                ),
                resolver().resolve(Set.of(appFile, infraFile)).dependencies()
        );
    }

    @Test
    void requireSyntaxProducesCorrectDependency() throws IOException {
        Path f = file("src/infrastructure/LegacyAdapter.ts", """
                const svc = require('../application/AppService');
                export class LegacyAdapter {}
                """);

        assertEquals(
                Set.of(new Dependency(infrastructure, application)),
                resolver().resolve(Set.of(f)).dependencies()
        );
    }

    @Test
    void multiLineImportResolvedCorrectly() throws IOException {
        Path f = file("src/application/Handler.ts", """
                import {
                  Entity,
                  ValueObject
                } from '../domain/Entity';
                export class Handler {}
                """);

        assertEquals(
                Set.of(new Dependency(application, domain)),
                resolver().resolve(Set.of(f)).dependencies()
        );
    }

    @Test
    void resolveWithLocationsTracksFileAndLine() throws IOException {
        Path f = file("src/domain/Entity.ts", """
                import { Repo } from '../infrastructure/Repo';
                export class Entity {}
                """);

        ResolvedDataWithLocations located = resolver().resolveWithLocations(Set.of(f));

        assertEquals(1, located.locatedDependencies().size());
        LocatedDependency loc = located.locatedDependencies().get(0);
        assertEquals(domain, loc.dependency().source());
        assertEquals(infrastructure, loc.dependency().target());
        assertEquals(1, loc.lineNumber());
        assertEquals(f, loc.sourceFile());
        assertTrue(loc.importText().contains("infrastructure"));
    }

    @Test
    void functionCountIsComputedPerModule() throws IOException {
        Path f = file("src/application/Service.ts", """
                export class Service {
                  public doSomething(): void {
                    const helper = () => {
                      console.log('hi');
                    };
                  }
                  public getValue(): number {
                    return 42;
                  }
                }
                """);

        var resolved = resolver().resolve(Set.of(f));
        int wmc = resolved.moduleWmc().getOrDefault(application, 0);
        assertTrue(wmc >= 2, "Expected at least 2 function/method declarations, got " + wmc);
    }
}
