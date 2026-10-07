package mmiv.service;

import mmiv.config.DiscoveryProperties;
import mmiv.model.DiscoveryFailureReason;
import mmiv.model.DiscoveryResult;
import mmiv.model.DiscoveryRunResult;
import mmiv.model.MediaMetadata;
import mmiv.model.MigrationFileInventory;
import mmiv.repository.FileMetadataRepository;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class DiscoveryService {

    private final FileMetadataRepository fileMetadataRepository;
    private final DiscoveryProperties discoveryProperties;

    public DiscoveryService(
            FileMetadataRepository fileMetadataRepository,
            DiscoveryProperties discoveryProperties) {

        this.fileMetadataRepository = fileMetadataRepository;
        this.discoveryProperties = discoveryProperties;
    }

    // Discover metadata for one file
    MediaMetadata discover(
            Path filePath,
            Path rootDirectory) throws IOException {

        BasicFileAttributes attributes =
                Files.readAttributes(
                        filePath,
                        BasicFileAttributes.class
                );

        String fileName =
                filePath.getFileName().toString();

        String mediaType =
                getFileExtension(fileName);

        long fileSize =
                attributes.size();

        Path parentDirectory =
                filePath.getParent();

        String srcDir =
                rootDirectory
                        .relativize(parentDirectory)
                        .toString();

        LocalDateTime createdDate =
                LocalDateTime.ofInstant(
                        attributes.creationTime().toInstant(),
                        ZoneId.systemDefault()
                );

        return new MediaMetadata(
                fileName,
                mediaType,
                fileSize,
                srcDir,
                createdDate
        );
    }

    // Discover one file and assign a formal discovery status
    public DiscoveryResult discoverFile(
            Path filePath,
            Path rootDirectory) {

        if (!isSupportedFile(filePath)) {

            return DiscoveryResult.skipped(
                    filePath,
                    DiscoveryFailureReason.UNSUPPORTED_FILE_TYPE,
                    "Unsupported file type"
            );
        }

        try {

            MediaMetadata metadata =
                    discover(
                            filePath,
                            rootDirectory
                    );

            return DiscoveryResult.accepted(
                    filePath,
                    metadata
            );

        } catch (IOException | SecurityException e) {

            return DiscoveryResult.failed(
                    filePath,
                    DiscoveryFailureReason.INACCESSIBLE_FILE,
                    "Unable to read file metadata: "
                            + e.getMessage()
            );
        }
    }

    // Scan directory and build the structured migration inventory
    public MigrationFileInventory discoverDirectory(
            Path rootDirectory) throws IOException {

        List<DiscoveryResult> results =
                new ArrayList<>();

        try (var paths = Files.walk(rootDirectory)) {

            paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredFile(path))
                    .forEach(path -> {

                        DiscoveryResult result =
                                discoverFile(
                                        path,
                                        rootDirectory
                                );

                        results.add(result);
                    });
        }

        return new MigrationFileInventory(results);
    }

    // Discover once, build inventory and persist accepted new files
    public DiscoveryRunResult discoverAndSaveWithResult(
            Path rootDirectory) throws IOException {

        MigrationFileInventory inventory =
                discoverDirectory(rootDirectory);

        int insertedCount = 0;

        for (MediaMetadata metadata :
                inventory.getAcceptedFiles()) {

            boolean alreadyExists =
                    fileMetadataRepository.exists(
                            metadata.getFileName(),
                            metadata.getSrcDir()
                    );

            if (!alreadyExists) {

                fileMetadataRepository.save(metadata);

                insertedCount++;
            }
        }

        return new DiscoveryRunResult(
                inventory,
                insertedCount
        );
    }

    // Keep existing API for compatibility
    public int discoverAndSave(
            Path rootDirectory) throws IOException {

        return discoverAndSaveWithResult(
                rootDirectory
        ).getInsertedCount();
    }

    // Ignore macOS system files
    private boolean isIgnoredFile(Path path) {

        String fileName =
                path.getFileName().toString();

        return fileName.equals(".DS_Store")
                || fileName.startsWith("._");
    }

    // Check whether file type is configured as supported
    private boolean isSupportedFile(Path path) {

        String fileName =
                path.getFileName().toString();

        String extension =
                getFileExtension(fileName);

        return discoveryProperties
                .getSupportedFileTypes()
                .stream()
                .anyMatch(type ->
                        type.equalsIgnoreCase(extension));
    }

    // Extract extension as media type
    private String getFileExtension(String fileName) {

        int dotIndex =
                fileName.lastIndexOf('.');

        if (dotIndex == -1
                || dotIndex == fileName.length() - 1) {

            return "UNKNOWN";
        }

        return fileName
                .substring(dotIndex + 1)
                .toUpperCase();
    }
}