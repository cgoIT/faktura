package de.cgoit.faktura.shared;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.cgoit.faktura.SqliteTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class ActuatorExposureTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsUpWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"UP\"")))
                .andExpect(content().string(not(containsString("sqlite"))))
                .andExpect(content().string(not(containsString("components"))));
    }

    @Test
    void otherEndpointsAreNotExposed() throws Exception {
        for (var path : new String[] { "/actuator/env", "/actuator/beans", "/actuator/info", "/actuator/metrics",
                "/actuator/configprops", "/actuator/loggers", "/actuator/heapdump" }) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
