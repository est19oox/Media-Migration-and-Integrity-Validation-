
package mmiv.controller;

import mmiv.service.DiscoveryService;
import mmiv.service.ManifestProcessingService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = DiscoveryController.class,
        properties = {
                "discovery.root=/test/media",
                "discovery.manifest-path="
        }
)
class DiscoveryMissingManifestWebTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean mmiv.events.EventPublisher auditPublisher;


    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiscoveryService discoveryService;

    @MockitoBean
    private ManifestProcessingService manifestProcessingService;

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
