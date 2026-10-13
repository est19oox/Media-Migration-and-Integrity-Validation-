package mmiv.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import mmiv.csv.MetadataCsvRecord;
import mmiv.csv.MetadataCsvValidationError;
import mmiv.csv.MetadataCsvValidationException;
import mmiv.model.ManifestMatchResult;
import mmiv.model.MediaMetadata;
import mmiv.repository.FileMetadataRepository;
import mmiv.repository.ValidationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestProcessingServiceTest {

  @TempDir Path tempDir;

  private ManifestDiscoveryService manifestDiscoveryService;
  private FileMetadataRepository fileMetadataRepository;
  private ValidationLogRepository validationLogRepository;
  private ManifestProcessingService processingService;

  private Path csvPath;
  private Path rootDirectory;

  @BeforeEach
  void setUp() {
    manifestDiscoveryService = mock(ManifestDiscoveryService.class);
    fileMetadataRepository = mock(FileMetadataRepository.class);
    validationLogRepository = mock(ValidationLogRepository.class);

    processingService =
        new ManifestProcessingService(
            manifestDiscoveryService, fileMetadataRepository, validationLogRepository);

    csvPath = tempDir.resolve("metadata.csv");
    rootDirectory = tempDir.resolve("files");
  }

  private MetadataCsvRecord record(String fileName) {
    return new MetadataCsvRecord(
        "M001",
        fileName,
        "VIDEO",
        "MP4",
        1000L,
        LocalDate.of(2026, 10, 5),
        "user01",
        "test",
        "Test file");
  }

  private MediaMetadata metadata(String fileName) {
    return new MediaMetadata(
        fileName, "MP4", 1000L, "videos", LocalDateTime.of(2026, 10, 5, 10, 0));
  }

  private void mockResults(ManifestMatchResult... results) throws IOException {
    when(manifestDiscoveryService.matchManifest(csvPath, rootDirectory))
        .thenReturn(List.of(results));
  }

  @Test
  void shouldSaveSuccessLogAndNewMetadata() throws Exception {
    MediaMetadata actual = metadata("video01.mp4");
    mockResults(ManifestMatchResult.success(record("video01.mp4"), actual));

    when(fileMetadataRepository.exists("video01.mp4", "videos")).thenReturn(false);

    List<ManifestMatchResult> results = processingService.processManifest(csvPath, rootDirectory);

    assertEquals(1, results.size());
    verify(validationLogRepository)
        .save(
            argThat(
                log ->
                    "SUCCESS".equals(log.validationStatus())
                        && "video01.mp4".equals(log.fileName())));
    verify(fileMetadataRepository).save(actual);
  }

  @Test
  void shouldNotInsertDuplicateMetadata() throws Exception {
    MediaMetadata actual = metadata("video01.mp4");
    mockResults(ManifestMatchResult.success(record("video01.mp4"), actual));

    when(fileMetadataRepository.exists("video01.mp4", "videos")).thenReturn(true);

    processingService.processManifest(csvPath, rootDirectory);

    verify(validationLogRepository).save(argThat(log -> "SUCCESS".equals(log.validationStatus())));
    verify(fileMetadataRepository, never()).save(any());
  }

  @Test
  void shouldSaveMismatchLogWithoutMetadata() throws Exception {
    mockResults(
        ManifestMatchResult.mismatch(
            record("video02.mp4"), metadata("video02.mp4"), "FILE_SIZE", "18000000"));

    processingService.processManifest(csvPath, rootDirectory);

    verify(validationLogRepository)
        .save(
            argThat(
                log ->
                    "MISMATCH".equals(log.validationStatus())
                        && "FILE_SIZE".equals(log.checkFailed())
                        && "18000000".equals(log.expectedValue())));
    verifyNoInteractions(fileMetadataRepository);
  }

  @Test
  void shouldLogMissingFileWithoutMetadata() throws Exception {
    mockResults(
        ManifestMatchResult.mismatch(record("missing.mp4"), null, "FILE_NOT_FOUND", "missing.mp4"));

    processingService.processManifest(csvPath, rootDirectory);

    verify(validationLogRepository)
        .save(argThat(log -> "FILE_NOT_FOUND".equals(log.checkFailed()) && log.fileSize() == null));
    verifyNoInteractions(fileMetadataRepository);
  }

  @Test
  void shouldLogAmbiguousFileName() throws Exception {
    mockResults(
        ManifestMatchResult.mismatch(
            record("video01.mp4"), null, "AMBIGUOUS_FILE_NAME", "video01.mp4"));

    processingService.processManifest(csvPath, rootDirectory);

    verify(validationLogRepository)
        .save(argThat(log -> "AMBIGUOUS_FILE_NAME".equals(log.checkFailed())));
    verifyNoInteractions(fileMetadataRepository);
  }

  @Test
  void shouldNotWriteDatabaseWhenCsvInvalid() throws Exception {
    when(manifestDiscoveryService.matchManifest(csvPath, rootDirectory))
        .thenThrow(
            new MetadataCsvValidationException(
                List.of(new MetadataCsvValidationError(2, "File Size", "Invalid numeric value"))));

    assertThrows(
        MetadataCsvValidationException.class,
        () -> processingService.processManifest(csvPath, rootDirectory));

    verifyNoInteractions(validationLogRepository, fileMetadataRepository);
  }
}
