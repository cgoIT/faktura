package de.cgoit.faktura.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.cgoit.faktura.SqliteTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class SecurityHeadersTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void pagesCarryAStrictContentSecurityPolicyAndOtherHeaders() throws Exception {
        mvc.perform(get("/invoices"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("object-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("X-Frame-Options"))
                .andExpect(header().string("Referrer-Policy", "same-origin"));
    }

    @Test
    void pagesCarryTheTokenForHtmxAndSwitchOffEval() throws Exception {
        mvc.perform(get("/invoices"))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("name=\"_csrf_header\"")))
                .andExpect(content().string(containsString("\"allowEval\":false")));
    }

    @Test
    void pagesShowTheDisclaimer() throws Exception {
        mvc.perform(get("/invoices"))
                .andExpect(content().string(containsString("Betrieb auf eigenes Risiko")));
    }
}
