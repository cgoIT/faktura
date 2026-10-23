package de.cgoit.faktura;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies module boundaries: no cycles, no access to internal packages of other modules.
 * Runs without a Spring context and without a database.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(FakturaApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }
}
