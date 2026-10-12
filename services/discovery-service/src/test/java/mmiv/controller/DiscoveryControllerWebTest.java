
package mmiv.controller;

import mmiv.csv.MetadataCsvValidationError;
import mmiv.csv.MetadataCsvValidationException;
import mmiv.dto.DiscoveryResponse;
import mmiv.model.DiscoveryRunResult;
import mmiv.model.ManifestMatchResult;
import mmiv.model.MigrationFileInventory;
import mmiv.service.DiscoveryService;
import mmiv.service.ManifestProcessingService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-level behaviour of the Discovery REST API.
 *
 * <p>Security filters are disabled here: this test covers response bodies and
 * error mapping, while bearer-token rules are covered separately by
 * {@code mmiv.security.DiscoverySecurityIntegrationTest}.
 */
@WebMvcTest(
        controllers = DiscoveryController.class,
        properties = {
                "discovery.root=/test/media",
                "discovery.manifest-path=/test/manifest.csv"
        }
)
@AutoConfigureMockMvc(addFilters = false)
class DiscoveryControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiscoveryService discoveryService;

    @MockitoBean
    private ManifestProcessingService manifestProcessingService;

    private final Path rootPath = Path.of("/test/media");
    private final Path csvPath = Path.of("/test/manifest.csv");

    @Test
    void scanShouldReturnHttp200AndSummary() throws Exception {

        DiscoveryRunResult result =
                new DiscoveryRunResult(
                        new MigrationFileInventory(List.of()),
                        0
                );

        when(discoveryService.discoverAndSaveWithResult(rootPath))
                .thenReturn(result);

        mockMvc.perform(post("/api/discovery/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discovered").value(0))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.inserted").value(0));

        verify(discoveryService).discoverAndSaveWithResult(rootPath);
    }

    @Test
    void manifestShouldReturnMatchedAndMismatchedCounts()
            throws Exception {

        ManifestMatchResult success =
                ManifestMatchResult.success(null, null);

        ManifestMatchResult mismatch =
                ManifestMatchResult.mismatch(
                        null, null, "FILE_SIZE", "100"
                );

        when(manifestProcessingService.processManifest(
                csvPath, rootPath
        )).thenReturn(List.of(success, mismatch));

        mockMvc.perform(post("/api/discovery/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.mismatched").value(1));
    }

    @Test
    void manifestShouldHandleEmptyResults() throws Exception {

        when(manifestProcessingService.processManifest(
                csvPath, rootPath
        )).thenReturn(List.of());

        mockMvc.perform(post("/api/discovery/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.matched").value(0))
                .andExpect(jsonPath("$.mismatched").value(0));
    }

    @Test
    void invalidCsvShouldReturnHttp400() throws Exception {

        MetadataCsvValidationException exception =
                new MetadataCsvValidationException(
                        List.of(new MetadataCsvValidationError(
                                2,
                                "File Size",
                                "Invalid numeric value"
                        ))
                );

        when(manifestProcessingService.processManifest(
                csvPath, rootPath
        )).thenThrow(exception);

        mockMvc.perform(post("/api/discovery/manifest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("CSV validation failed"))
                .andExpect(jsonPath(
                        "$.validationErrors[0].rowNumber"
                ).value(2))
                .andExpect(jsonPath(
                        "$.validationErrors[0].fieldName"
                ).value("File Size"));
    }

    @Test
    void missingCsvShouldReturnHttp404() throws Exception {

        MetadataCsvValidationException exception =
                new MetadataCsvValidationException(
                        List.of(new MetadataCsvValidationError(
                                0,
                                "File",
                                "CSV file does not exist"
                        ))
                );

        when(manifestProcessingService.processManifest(
                csvPath, rootPath
        )).thenThrow(exception);

        mockMvc.perform(post("/api/discovery/manifest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message")
                        .value("CSV manifest file not found"));
    }

    @Test
    void databaseErrorShouldReturnHttp503() throws Exception {

        when(manifestProcessingService.processManifest(
                csvPath, rootPath
        )).thenThrow(new DataIntegrityViolationException(
                "Database error"
        ));

        mockMvc.perform(post("/api/discovery/manifest"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message")
                        .value("Database operation failed"));
    }

    @Test
    void ioErrorShouldReturnHttp500() throws Exception {

        when(discoveryService.discoverAndSaveWithResult(rootPath))
                .thenThrow(new IOException("File read failed"));

        mockMvc.perform(post("/api/discovery/scan"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message")
                        .value("File processing failed"));
    }

    @Test
    void unknownPathShouldReturnHttp404() throws Exception {

        mockMvc.perform(get("/api/discovery/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongHttpMethodShouldReturnHttp405() throws Exception {

        mockMvc.perform(get("/api/discovery/scan"))
                .andExpect(status().isMethodNotAllowed());
    }
}
