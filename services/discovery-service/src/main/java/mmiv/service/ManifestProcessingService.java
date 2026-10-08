
package mmiv.service;

import mmiv.model.ManifestMatchResult;
import mmiv.model.MediaMetadata;
import mmiv.model.ValidationLog;
import mmiv.repository.FileMetadataRepository;
import mmiv.repository.ValidationLogRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
public class ManifestProcessingService {

    private final ManifestDiscoveryService manifestDiscoveryService;
    private final FileMetadataRepository fileMetadataRepository;
    private final ValidationLogRepository validationLogRepository;

    public ManifestProcessingService(
            ManifestDiscoveryService manifestDiscoveryService,
            FileMetadataRepository fileMetadataRepository,
            ValidationLogRepository validationLogRepository
    ) {
        this.manifestDiscoveryService = manifestDiscoveryService;
        this.fileMetadataRepository = fileMetadataRepository;
        this.validationLogRepository = validationLogRepository;
    }

    /**
     * Process a validated CSV manifest and persist results.
     *
     * Invalid CSV manifests are rejected before database writes.
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ManifestMatchResult> processManifest(
            Path csvPath,
            Path rootDirectory
    ) throws IOException {

        // Step 1: Validate CSV and match actual files.
        List<ManifestMatchResult> results =
                manifestDiscoveryService.matchManifest(
                        csvPath,
                        rootDirectory
                );

        // Step 2: Persist validation logs for all match results.
        for (ManifestMatchResult result : results) {

            ValidationLog log =
                    ValidationLog.from(result);

            validationLogRepository.save(log);

            // Step 3: Persist only successful new metadata.
            if (result.isSuccess()) {

                MediaMetadata metadata =
                        result.actualMetadata();

                boolean alreadyExists =
                        fileMetadataRepository.exists(
                                metadata.getFileName(),
                                metadata.getSrcDir()
                        );

                if (!alreadyExists) {
                    fileMetadataRepository.save(metadata);
                }
            }
        }

        return results;
    }
}
