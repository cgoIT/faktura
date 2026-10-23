package de.cgoit.faktura.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every route that changes something is closed to a request without a CSRF token. The routes come from the handler
 * mapping, so a new controller method is covered without touching this test.
 */
@SqliteTest
class CsrfProtectionTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Test
    void everyPostRouteRejectsARequestWithoutToken() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        var paths = postRoutes();

        assertThat(paths).hasSizeGreaterThan(40);

        for (var path : paths) {
            mvc.perform(post(path))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aRequestWithATokenGetsPastTheProtection() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        mvc.perform(post("/mail/preview").with(csrf()))
                .andExpect(status().isOk());
    }

    private List<String> postRoutes() {
        var paths = new ArrayList<String>();

        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (info.getMethodsCondition().getMethods().contains(RequestMethod.POST)) {
                info.getPathPatternsCondition().getPatterns().forEach(pattern ->
                        paths.add(pattern.getPatternString().replaceAll("\\{[^}]+}", "x")));
            }
        });

        return paths;
    }
}
