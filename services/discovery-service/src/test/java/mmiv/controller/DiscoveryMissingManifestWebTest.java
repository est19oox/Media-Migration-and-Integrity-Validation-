package mmiv.controller;

import mmiv.events.DiscoveryEventPublisher;
import mmiv.service.DiscoveryService;
import mmiv.service.ManifestProcessingService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The unconfigured-manifest path returns HTTP 503.
 *
 * <p>Security filters are disabled here: this test covers the missing
 * configuration response, while bearer-token rules are covered separately by
 * {@code mmiv.security.DiscoverySecurityIntegrationTest}.
 *
 * <p>The DiscoveryEventPublisher is mocked so the controller can be
 * instantiated without requiring a real RabbitMQ connection.
 */
@WebMvcTest(
        controllers = DiscoveryController.class,
        properties = {
                "discovery.root=/test/media",
                "discovery.manifest-path="
        }
)
@AutoConfigureMockMvc(addFilters = false)
class DiscoveryMissingManifestWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiscoveryService discoveryService;

    @MockitoBean
    private ManifestProcessingService manifestProcessingService;

    // Mock RabbitMQ publisher to satisfy controller dependency.
    @MockitoBean
    private DiscoveryEventPublisher eventPublisher;

    @Test
    void missingManifestConfigurationShouldReturnHttp503()
            throws Exception {

        mockMvc.perform(post("/api/discovery/manifest"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message")
                        .value("CSV manifest path is not configured"))
                .andExpect(jsonPath("$.path")
                        .value("/api/discovery/manifest"));

        verifyNoInteractions(manifestProcessingService);
    }
}
