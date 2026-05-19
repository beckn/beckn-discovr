package org.beckn.catalogpublish.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ActuatorPrefixIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void actuatorHealth_atStandardPath_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorMetrics_atStandardPath_returns200() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealth_underBecknPrefix_returns404() throws Exception {
        mockMvc.perform(get("/beckn/actuator/health"))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorMetrics_underBecknPrefix_returns404() throws Exception {
        mockMvc.perform(get("/beckn/actuator/metrics"))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorPrometheus_underBecknPrefix_returns404() throws Exception {
        mockMvc.perform(get("/beckn/actuator/prometheus"))
                .andExpect(status().isNotFound());
    }
}
