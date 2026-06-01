package org.beckn.discover.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ActuatorPrefixIntegrationTest extends BaseIntegrationTest {

    private static final String ACTUATOR_HEALTH_PATH     = "/actuator/health";
    private static final String ACTUATOR_METRICS_PATH    = "/actuator/metrics";
    private static final String ACTUATOR_PROMETHEUS_PATH = "/actuator/prometheus";
    // Actuator must NOT be exposed under the /beckn prefix
    private static final String BECKN_ACTUATOR_HEALTH_PATH     = "/beckn/actuator/health";
    private static final String BECKN_ACTUATOR_METRICS_PATH    = "/beckn/actuator/metrics";
    private static final String BECKN_ACTUATOR_PROMETHEUS_PATH = "/beckn/actuator/prometheus";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void actuatorHealth_atStandardPath_isReachable() throws Exception {
        mockMvc.perform(get(ACTUATOR_HEALTH_PATH))
                .andExpect(status().is(org.hamcrest.Matchers.not(404)));
    }

    @Test
    void actuatorMetrics_atStandardPath_isReachable() throws Exception {
        mockMvc.perform(get(ACTUATOR_METRICS_PATH))
                .andExpect(status().is(org.hamcrest.Matchers.not(404)));
    }

    @Test
    void actuatorPrometheus_atStandardPath_isReachable() throws Exception {
        mockMvc.perform(get(ACTUATOR_PROMETHEUS_PATH))
                .andExpect(status().is(org.hamcrest.Matchers.not(404)));
    }

    @Test
    void actuatorHealth_underBecknPrefix_returns404() throws Exception {
        mockMvc.perform(get(BECKN_ACTUATOR_HEALTH_PATH))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorMetrics_underBecknPrefix_returns404() throws Exception {
        mockMvc.perform(get(BECKN_ACTUATOR_METRICS_PATH))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorPrometheus_underBecknPrefix_returns404() throws Exception {
        mockMvc.perform(get(BECKN_ACTUATOR_PROMETHEUS_PATH))
                .andExpect(status().isNotFound());
    }
}
