package com.harmoniasuite.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.harmoniasuite",
        importOptions = ImportOption.DoNotIncludeTests.class)
class BackendArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_framework_independent = noClasses()
            .that().resideInAnyPackage("com.harmoniasuite.source.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.fasterxml..", "org.slf4j..",
                    "java.sql..", "jakarta.servlet..", "com.harmoniasuite.source.api..",
                    "com.harmoniasuite.source.application..",
                    "com.harmoniasuite.source.infrastructure..");

    @ArchTest
    static final ArchRule application_is_framework_independent = noClasses()
            .that().resideInAnyPackage("com.harmoniasuite.source.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.fasterxml..", "org.slf4j..", "java.sql..", "jakarta.servlet..",
                    "com.harmoniasuite.source.api..", "com.harmoniasuite.source.infrastructure..");

    @ArchTest
    static final ArchRule infrastructure_does_not_depend_on_api = noClasses()
            .that().resideInAnyPackage("com.harmoniasuite.source.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("com.harmoniasuite.source.api..");

    @ArchTest
    static final ArchRule api_does_not_access_persistence_or_filesystem = noClasses()
            .that().resideInAnyPackage("com.harmoniasuite.source.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.jdbc..", "java.nio.file..", "java.sql..");

    @ArchTest
    static final ArchRule no_legacy_global_architecture_buckets = classes()
            .that().resideInAnyPackage("com.harmoniasuite.controller..",
                    "com.harmoniasuite.dto..", "com.harmoniasuite.service..",
                    "com.harmoniasuite.repository..", "com.harmoniasuite.mapping..",
                    "com.harmoniasuite.domain..",
                    "com.harmoniasuite.source.store..", "com.harmoniasuite.source.artifact..",
                    "com.harmoniasuite.source.upload..").should().haveSimpleName("NoLegacyClasses")
            .allowEmptyShould(true);
}
