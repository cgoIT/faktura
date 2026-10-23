package de.cgoit.faktura;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Lets every MockMvc request of the tests carry a valid CSRF token, so page tests need not care about it. Tests of the
 * protection itself build their own MockMvc without this.
 */
@TestConfiguration
public class TestSecurityConfiguration {

    @Bean
    MockMvcBuilderCustomizer csrfForEveryRequest() {
        return builder -> builder.defaultRequest(get("/").with(csrf()));
    }
}
