package dev.healthintelligence.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.UserId;
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

    /**
     * Every query a clinical repository declares (find/exists/count/delete/get/read/query/stream...)
     * takes the owner as a typed {@link PatientId}, or {@link UserId} for the patient profile itself.
     * Only writes of an entity already loaded through such a query are exempt.
     */
    @ArchTest
    static final ArchRule clinicalRepositoryQueriesAreScopedToTheOwner = methods()
            .that().areDeclaredInClassesThat().resideInAnyPackage(CLINICAL)
            .and().areDeclaredInClassesThat().areAssignableTo(Repository.class)
            .and().areDeclaredInClassesThat().doNotHaveFullyQualifiedName(
                    "dev.healthintelligence.core.laboratory.AnalyteCatalogRepository")
            .and().doNotHaveName("save").and().doNotHaveName("saveAndFlush")
            .and().doNotHaveName("delete").and().doNotHaveName("flush")
            .should(takeAnOwnerParameter())
            .because("a lookup without the owner can return another patient's data (ADR 0011)");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositoriesDirectly = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .because("use cases (and their patient scoping) live in services");

    private static ArchCondition<JavaMethod> takeAnOwnerParameter() {
        return new ArchCondition<>("take a PatientId or UserId parameter") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean scoped = method.getRawParameterTypes().stream()
                        .anyMatch(type -> type.isEquivalentTo(PatientId.class) || type.isEquivalentTo(UserId.class));
                if (!scoped) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName() + " has no owner parameter"));
                }
            }
        };
    }

    @ArchTest
    static final ArchRule controllersAreNotPublic = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().notBePublic()
            .because("web adapters are internal to their module");
}
