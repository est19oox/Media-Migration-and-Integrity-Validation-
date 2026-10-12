
package mmiv.controller;

import mmiv.dto.DiscoveryResponse;
import mmiv.dto.ManifestResponse;
import mmiv.model.DiscoveryRunResult;
import mmiv.model.ManifestMatchResult;
import mmiv.model.MigrationFileInventory;
import mmiv.service.DiscoveryService;
import mmiv.service.ManifestProcessingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryControllerTest {

    private DiscoveryService discoveryService;
    private ManifestProcessingService manifestProcessingService;
    private DiscoveryController controller;

    private final Path rootPath = Path.of("/test/media");
    private final Path csvPath = Path.of("/test/manifest.csv");

    @BeforeEach
    void setUp() {

        discoveryService = mock(DiscoveryService.class);
        manifestProcessingService =
                mock(ManifestProcessingService.class);

        controller = new DiscoveryController(
                discoveryService,
                manifestProcessingService,
                rootPath.toString(),
                csvPath.toString()
        );
    }

    @Test
    void scanDirectoryShouldReturnDiscoverySummary()
            throws IOException {

        DiscoveryRunResult runResult =
                new DiscoveryRunResult(
                        new MigrationFileInventory(List.of()),
                        0
                );

        when(discoveryService.discoverAndSaveWithResult(rootPath))
                .thenReturn(runResult);

        ResponseEntity<DiscoveryResponse> response =
                controller.scanDirectory();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().discovered());
        assertEquals(0, response.getBody().inserted());

        verify(discoveryService).discoverAndSaveWithResult(rootPath);
    }

    @Test
    void scanDirectoryShouldPropagateIOException()
            throws IOException {

        when(discoveryService.discoverAndSaveWithResult(rootPath))
                .thenThrow(new IOException("Scan failed"));

        assertThrows(
                IOException.class,
                () -> controller.scanDirectory()
        );
    }

    @Test
    void processManifestShouldReturnSummary()
            throws IOException {

        when(manifestProcessingService.processManifest(
                csvPath,
                rootPath
        )).thenReturn(List.of());

        ResponseEntity<ManifestResponse> response =
                controller.processManifest();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().total());
        assertEquals(0, response.getBody().matched());
        assertEquals(0, response.getBody().mismatched());

        verify(manifestProcessingService)
                .processManifest(csvPath, rootPath);
    }

    @Test
    void processManifestShouldPropagateIOException()
            throws IOException {

        when(manifestProcessingService.processManifest(
                csvPath,
                rootPath
        )).thenThrow(new IOException("CSV not found"));

        assertThrows(
                IOException.class,
                () -> controller.processManifest()
        );
    }

@Test
void processManifestShouldCountMatchesAndMismatches()
        throws IOException {

    ManifestMatchResult success =
            ManifestMatchResult.success(null, null);

    ManifestMatchResult mismatch =
            ManifestMatchResult.mismatch(
                    null, null, "FILE_SIZE", "100"
            );

    when(manifestProcessingService.processManifest(
            csvPath, rootPath
    )).thenReturn(List.of(success, mismatch));

    ResponseEntity<ManifestResponse> response =
            controller.processManifest();

    assertNotNull(response.getBody());
    assertEquals(2, response.getBody().total());
    assertEquals(1, response.getBody().matched());
    assertEquals(1, response.getBody().mismatched());
}

@Test
void processManifestShouldRejectMissingConfiguration() {

    DiscoveryController unconfiguredController =
            new DiscoveryController(
                    discoveryService,
                    manifestProcessingService,
                    rootPath.toString(),
                    ""
            );

    org.springframework.web.server.ResponseStatusException exception =
            assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    unconfiguredController::processManifest
            );

    assertEquals(
            503,
            exception.getStatusCode().value()
    );

    verifyNoInteractions(manifestProcessingService);
}

}
