package dev.healthintelligence.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/** Patient isolation rules that must hold for every module (ADR 0011). */
@AnalyzeClasses(packages = "dev.healthintelligence.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    /** Modules that hold patient-owned data. */
    private static final String[] CLINICAL = {
            "..core.patient..", "..core.healthrecords..", "..core.documents..", "..core.laboratory..",
            "..core.timeline..", "..core.analytics..", "..core.assistant.."};

    /**
     * CrudRepository brings {@code findById(UUID)}, {@code findAll()} and {@code deleteById(UUID)}:
     * lookups that are not scoped to a patient. Clinical repositories declare their methods explicitly.
     */
    @ArchTest
    static final ArchRule clinicalRepositoriesDoNotInheritUnscopedLookups = noClasses()
            .that().resideInAnyPackage(CLINICAL)
            .should().beAssignableTo(CrudRepository.class)
            .because("patient-owned data is only reachable through the owner (ADR 0011)");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositoriesDirectly = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .because("use cases (and their patient scoping) live in services");

    @ArchTest
    static final ArchRule controllersAreNotPublic = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().notBePublic()
            .because("web adapters are internal to their module");
}
