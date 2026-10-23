package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevProfileGuardTest {

    @Test
    void devProfileInTheContainerIsRefused() {
        var environment = new MockEnvironment().withProperty("FAKTURA_CONTAINER", "true");
        environment.setActiveProfiles("dev");

        assertThatThrownBy(() -> DevProfileGuard.check(environment)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devProfileOutsideTheContainerIsAllowed() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThatCode(() -> DevProfileGuard.check(environment)).doesNotThrowAnyException();
    }

    @Test
    void containerWithoutDevProfileIsAllowed() {
        var environment = new MockEnvironment().withProperty("FAKTURA_CONTAINER", "true");

        assertThatCode(() -> DevProfileGuard.check(environment)).doesNotThrowAnyException();
    }

    @Test
    void emptyContainerVariableCountsAsNotInAContainer() {
        var environment = new MockEnvironment().withProperty("FAKTURA_CONTAINER", " ");
        environment.setActiveProfiles("dev");

        assertThatCode(() -> DevProfileGuard.check(environment)).doesNotThrowAnyException();
    }
}
