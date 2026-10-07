package mmiv.service;

import mmiv.config.DiscoveryProperties;
import mmiv.model.DiscoveryFailureReason;
import mmiv.model.DiscoveryResult;
import mmiv.model.DiscoveryStatus;
import mmiv.model.DiscoverySummary;
import mmiv.model.MediaMetadata;
import mmiv.model.MigrationFileInventory;
import mmiv.repository.FileMetadataRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscoveryServiceTest {

    private FileMetadataRepository repository;
    private DiscoveryService discoveryService;

    @BeforeEach
    void setUp() {

        repository =
                mock(FileMetadataRepository.class);

        DiscoveryProperties discoveryProperties =
                new DiscoveryProperties();

        discoveryProperties.setSupportedFileTypes(
                List.of(
                        "MP4",
                        "PDF",
                        "MP3",
                        "PPT",
                        "PPTX",
                        "PNG"
                )
        );

        discoveryService =
                new DiscoveryService(
                        repository,
                        discoveryProperties
                );
    }

    @Test
    void shouldDiscoverFileMetadata(
            @TempDir Path tempDir) throws IOException {

        Path file =
                tempDir.resolve("lecture.pdf");

        Files.writeString(
                file,
                "test content"
        );

        MediaMetadata metadata =
                discoveryService.discover(
                        file,
                        tempDir
                );

        assertEquals(
                "lecture.pdf",
                metadata.getFileName()
        );

        assertEquals(
                "PDF",
                metadata.getMediaType()
        );

        assertTrue(
                metadata.getFileSize() > 0
        );

        assertEquals(
                "",
                metadata.getSrcDir()
        );

        assertNotNull(
                metadata.getCreatedDate()
        );
    }

    @Test
    void shouldAcceptSupportedFile(
            @TempDir Path tempDir) throws IOException {

        Path file =
                tempDir.resolve("lecture.pdf");

        Files.writeString(
                file,
                "pdf content"
        );

        DiscoveryResult result =
                discoveryService.discoverFile(
                        file,
                        tempDir
                );

        assertEquals(
                DiscoveryStatus.ACCEPTED,
                result.getStatus()
        );

        assertNotNull(
                result.getMetadata()
        );

        assertEquals(
                "lecture.pdf",
                result.getMetadata().getFileName()
        );

        assertNull(
                result.getReason()
        );

        assertNull(
                result.getMessage()
        );
    }

    @Test
    void shouldAcceptPptxFile(
            @TempDir Path tempDir) throws IOException {

        Path file =
                tempDir.resolve("slides.pptx");

        Files.writeString(
                file,
                "pptx content"
        );

        DiscoveryResult result =
                discoveryService.discoverFile(
                        file,
                        tempDir
                );

        assertEquals(
                DiscoveryStatus.ACCEPTED,
                result.getStatus()
        );

        assertNotNull(
                result.getMetadata()
        );

        assertEquals(
                "PPTX",
                result.getMetadata().getMediaType()
        );
    }

    @Test
    void shouldSkipUnsupportedFile(
            @TempDir Path tempDir) throws IOException {

        Path file =
                tempDir.resolve("notes.txt");

        Files.writeString(
                file,
                "text content"
        );

        DiscoveryResult result =
                discoveryService.discoverFile(
                        file,
                        tempDir
                );

        assertEquals(
                DiscoveryStatus.SKIPPED,
                result.getStatus()
        );

        assertNull(
                result.getMetadata()
        );

        assertEquals(
                DiscoveryFailureReason.UNSUPPORTED_FILE_TYPE,
                result.getReason()
        );

        assertEquals(
                "Unsupported file type",
                result.getMessage()
        );
    }

    @Test
    void shouldReturnFailedForInaccessibleFile(
            @TempDir Path tempDir) {

        Path missingFile =
                tempDir.resolve("missing.pdf");

        DiscoveryResult result =
                discoveryService.discoverFile(
                        missingFile,
                        tempDir
                );

        assertEquals(
                DiscoveryStatus.FAILED,
                result.getStatus()
        );

        assertEquals(
                DiscoveryFailureReason.INACCESSIBLE_FILE,
                result.getReason()
        );

        assertNull(
                result.getMetadata()
        );

        assertNotNull(
                result.getMessage()
        );

        assertTrue(
                result.getMessage()
                        .startsWith(
                                "Unable to read file metadata:"
                        )
        );
    }

    @Test
    void shouldBuildStructuredMigrationInventory(
            @TempDir Path tempDir) throws IOException {

        Files.writeString(
                tempDir.resolve("lecture.pdf"),
                "pdf"
        );

        Files.writeString(
                tempDir.resolve("video.mp4"),
                "video"
        );

        Files.writeString(
                tempDir.resolve("notes.txt"),
                "text"
        );

        MigrationFileInventory inventory =
                discoveryService.discoverDirectory(
                        tempDir
                );

        assertNotNull(
                inventory
        );

        assertEquals(
                3,
                inventory.getFiles().size()
        );

        assertEquals(
                2,
                inventory.getAcceptedFiles().size()
        );
    }

    @Test
    void shouldGenerateCorrectDiscoverySummary(
            @TempDir Path tempDir) throws IOException {

        Files.writeString(
                tempDir.resolve("lecture.pdf"),
                "pdf"
        );

        Files.writeString(
                tempDir.resolve("video.mp4"),
                "video"
        );

        Files.writeString(
                tempDir.resolve("notes.txt"),
                "text"
        );

        MigrationFileInventory inventory =
                discoveryService.discoverDirectory(
                        tempDir
                );

        DiscoverySummary summary =
                inventory.getSummary();

        assertEquals(
                3,
                summary.getDiscovered()
        );

        assertEquals(
                2,
                summary.getAccepted()
        );

        assertEquals(
                1,
                summary.getSkipped()
        );

        assertEquals(
                0,
                summary.getFailed()
        );
    }

    @Test
    void shouldIgnoreMacSystemFiles(
            @TempDir Path tempDir) throws IOException {

        Files.writeString(
                tempDir.resolve("lecture.pdf"),
                "pdf"
        );

        Files.writeString(
                tempDir.resolve(".DS_Store"),
                "system"
        );

        Files.writeString(
                tempDir.resolve("._lecture.pdf"),
                "system"
        );

        MigrationFileInventory inventory =
                discoveryService.discoverDirectory(
                        tempDir
                );

        assertEquals(
                1,
                inventory.getFiles().size()
        );

        assertEquals(
                1,
                inventory.getSummary().getDiscovered()
        );

        assertEquals(
                1,
                inventory.getSummary().getAccepted()
        );
    }

    @Test
    void shouldSaveAcceptedNewFile(
            @TempDir Path tempDir) throws IOException {

        Files.writeString(
                tempDir.resolve("lecture.pdf"),
                "pdf"
        );

        when(
                repository.exists(
                        any(),
                        any()
                )
        ).thenReturn(false);

        int insertedCount =
                discoveryService.discoverAndSave(
                        tempDir
                );

        assertEquals(
                1,
                insertedCount
        );

        verify(repository)
                .save(any(MediaMetadata.class));
    }

    @Test
    void shouldNotSaveDuplicateFile(
            @TempDir Path tempDir) throws IOException {

        Files.writeString(
                tempDir.resolve("lecture.pdf"),
                "pdf"
        );

        when(
                repository.exists(
                        any(),
                        any()
                )
        ).thenReturn(true);

        int insertedCount =
                discoveryService.discoverAndSave(
                        tempDir
                );

        assertEquals(
                0,
                insertedCount
        );

        verify(
                repository,
                never()
        ).save(any(MediaMetadata.class));
    }

    @Test
    void shouldNotSaveSkippedFile(
            @TempDir Path tempDir) throws IOException {

        Files.writeString(
                tempDir.resolve("notes.txt"),
                "text"
        );

        int insertedCount =
                discoveryService.discoverAndSave(
                        tempDir
                );

        assertEquals(
                0,
                insertedCount
        );

        verify(
                repository,
                never()
        ).save(any(MediaMetadata.class));
    }

    @Test
    void shouldContinueScanningWhenOneFileFails(
            @TempDir Path tempDir) throws IOException {

        Path goodPdf =
                tempDir.resolve("good.pdf");

        Path badPdf =
                tempDir.resolve("bad.pdf");

        Path goodMp4 =
                tempDir.resolve("good.mp4");

        Files.writeString(
                goodPdf,
                "good pdf"
        );

        Files.writeString(
                badPdf,
                "bad pdf"
        );

        Files.writeString(
                goodMp4,
                "good video"
        );

        DiscoveryService spyService =
                spy(discoveryService);

        doThrow(
                new IOException(
                        "Simulated file read failure"
                )
        ).when(spyService)
                .discover(
                        eq(badPdf),
                        eq(tempDir)
                );

        MigrationFileInventory inventory =
                spyService.discoverDirectory(
                        tempDir
                );

        DiscoverySummary summary =
                inventory.getSummary();

        assertEquals(
                3,
                summary.getDiscovered()
        );

        assertEquals(
                2,
                summary.getAccepted()
        );

        assertEquals(
                0,
                summary.getSkipped()
        );

        assertEquals(
                1,
                summary.getFailed()
        );

        DiscoveryResult failedResult =
                inventory.getFiles()
                        .stream()
                        .filter(result ->
                                result.getStatus()
                                        == DiscoveryStatus.FAILED)
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                badPdf,
                failedResult.getFilePath()
        );

        assertEquals(
                DiscoveryFailureReason.INACCESSIBLE_FILE,
                failedResult.getReason()
        );

        assertTrue(
                failedResult.getMessage()
                        .contains(
                                "Simulated file read failure"
                        )
        );
    }
}