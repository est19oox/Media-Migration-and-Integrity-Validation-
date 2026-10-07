package mmiv.service;

import mmiv.model.MediaMetadata;
import mmiv.repository.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DiscoveryServiceTest {

    private DiscoveryService discoveryService;

    @BeforeEach
    void setUp() {

        FileMetadataRepository repository =
                mock(FileMetadataRepository.class);

        discoveryService =
                new DiscoveryService(repository);
    }

    @Test
    void shouldDiscoverFileMetadata() throws Exception {

        Path rootDirectory =
                Files.createTempDirectory("discovery-test");

        Path testFile =
                rootDirectory.resolve("sample.pdf");

        Files.writeString(
                testFile,
                "test content"
        );

        MediaMetadata metadata =
                discoveryService.discover(
                        testFile,
                        rootDirectory
                );

        assertEquals(
                "sample.pdf",
                metadata.getFileName()
        );

        assertEquals(
                "PDF",
                metadata.getMediaType()
        );

        assertEquals(
                Files.size(testFile),
                metadata.getFileSize()
        );

        assertEquals(
                "",
                metadata.getSrcDir()
        );

        assertNotNull(
                metadata.getCreatedDate()
        );
    }
}