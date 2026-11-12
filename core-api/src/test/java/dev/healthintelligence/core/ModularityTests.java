package dev.healthintelligence.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build when a module reaches into another module's internals (ADR 0001). */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(CoreApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }

    @Test
    void allPlannedModulesAreDetected() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .contains("identity", "consent", "patient", "healthrecords", "documents", "laboratory",
                        "timeline", "analytics", "assistant", "notifications", "audit", "shared");
    }
}
