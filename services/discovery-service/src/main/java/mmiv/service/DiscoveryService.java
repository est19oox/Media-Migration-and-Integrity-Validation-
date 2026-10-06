package mmiv.service;

import mmiv.model.MediaMetadata;
import mmiv.repository.FileMetadataRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class DiscoveryService {

    private final FileMetadataRepository fileMetadataRepository;

    public DiscoveryService(FileMetadataRepository fileMetadataRepository) {
        this.fileMetadataRepository = fileMetadataRepository;
    }

    // Discover metadata for one file
    public MediaMetadata discover(
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

        // Store relative path instead of Mac absolute path
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

    // Recursively discover all files in the directory
    public List<MediaMetadata> discoverDirectory(
            Path rootDirectory) throws IOException {

        try (var paths = Files.walk(rootDirectory)) {

            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredFile(path))
                    .map(path -> {
                        try {
                            return discover(
                                    path,
                                    rootDirectory
                            );
                        } catch (IOException e) {
                            throw new RuntimeException(
                                    "Failed to discover file: " + path,
                                    e
                            );
                        }
                    })
                    .toList();
        }
    }

    // Discover files and persist new metadata into Oracle
    public int discoverAndSave(
            Path rootDirectory) throws IOException {

        List<MediaMetadata> discoveredFiles =
                discoverDirectory(rootDirectory);

        int insertedCount = 0;

        for (MediaMetadata metadata : discoveredFiles) {

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

        return insertedCount;
    }

    // Ignore macOS system files
    private boolean isIgnoredFile(Path path) {

        String fileName =
                path.getFileName().toString();

        return fileName.equals(".DS_Store")
                || fileName.startsWith("._");
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