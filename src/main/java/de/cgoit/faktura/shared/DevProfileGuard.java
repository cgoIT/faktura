package de.cgoit.faktura.shared;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Refuses to start the application with the {@code dev} profile inside the container. The dev profile resets the
 * database and fills it with test data at every start, which would destroy production data. The container image sets
 * {@code FAKTURA_CONTAINER}.
 */
public final class DevProfileGuard {

    static final String CONTAINER_VARIABLE = "FAKTURA_CONTAINER";

    private DevProfileGuard() {
    }

    /**
     * Throws if the environment runs in the container and has the dev profile.
     *
     * @throws IllegalStateException if both are the case
     */
    public static void check(Environment environment) {
        var container = environment.getProperty(CONTAINER_VARIABLE);

        if (container != null && !container.isBlank() && environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException(
                    "The dev profile resets the database and must never run in the container");
        }
    }
}
