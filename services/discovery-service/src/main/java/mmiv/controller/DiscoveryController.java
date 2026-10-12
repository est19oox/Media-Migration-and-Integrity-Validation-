
package mmiv.controller;

import mmiv.dto.DiscoveryResponse;
import mmiv.dto.ManifestResponse;
import mmiv.model.DiscoveryRunResult;
import mmiv.model.DiscoverySummary;
import mmiv.model.ManifestMatchResult;
import mmiv.service.DiscoveryService;
import mmiv.service.ManifestProcessingService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private final DiscoveryService discoveryService;
    private final ManifestProcessingService manifestProcessingService;
    private final Path rootDirectory;
    private final Path manifestPath;

    public DiscoveryController(
            DiscoveryService discoveryService,
            ManifestProcessingService manifestProcessingService,
            @Value("${discovery.root}") String rootDirectory,
            @Value("${discovery.manifest-path}") String manifestPath) {

        this.discoveryService = discoveryService;
        this.manifestProcessingService = manifestProcessingService;
        this.rootDirectory = Path.of(rootDirectory);
        this.manifestPath =
        manifestPath == null || manifestPath.isBlank()
                ? null
                : Path.of(manifestPath);
    }

    /**
     * Trigger directory-based discovery.
     */
    @PostMapping("/scan")
    public ResponseEntity<DiscoveryResponse> scanDirectory()
            throws IOException {

        DiscoveryRunResult result =
                discoveryService.discoverAndSaveWithResult(rootDirectory);

        DiscoverySummary summary =
                result.getInventory().getSummary();

        DiscoveryResponse response = new DiscoveryResponse(
                summary.getDiscovered(),
                summary.getAccepted(),
                summary.getSkipped(),
                summary.getFailed(),
                result.getInsertedCount()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Process a CSV manifest against the configured discovery root.
     */
    @PostMapping("/manifest")
    public ResponseEntity<ManifestResponse> processManifest()
            throws IOException {
        if (manifestPath == null) {
            throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "CSV manifest path is not configured"
     );
    }


        List<ManifestMatchResult> results =
                manifestProcessingService.processManifest(
                        manifestPath,
                        rootDirectory
                );

        long matched = results.stream()
                .filter(ManifestMatchResult::isSuccess)
                .count();

        ManifestResponse response = new ManifestResponse(
                results.size(),
                matched,
                results.size() - matched
        );

        return ResponseEntity.ok(response);
    }
}
