package mmiv.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import mmiv.csv.MetadataCsvRecord;
import mmiv.csv.MetadataCsvService;
import mmiv.csv.MetadataCsvValidationError;
import mmiv.csv.MetadataCsvValidationException;
import mmiv.model.DiscoveryResult;
import mmiv.model.ManifestMatchResult;
import mmiv.model.MediaMetadata;
import mmiv.model.MigrationFileInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestDiscoveryServiceTest {

  @TempDir Path tempDir;

  private MetadataCsvService csvService;
  private DiscoveryService discoveryService;
  private ManifestDiscoveryService manifestService;

  @BeforeEach
  void setUp() {
    csvService = mock(MetadataCsvService.class);
    discoveryService = mock(DiscoveryService.class);

    manifestService = new ManifestDiscoveryService(csvService, discoveryService);
  }

  private MetadataCsvRecord csvRecord(String fileName, String format, long fileSize) {
    return new MetadataCsvRecord(
        "M001",
        fileName,
        "VIDEO",
        format,
        fileSize,
        LocalDate.of(2026, 10, 5),
        "user01",
        "test",
        "Test file");
  }

  private MediaMetadata actualMetadata(String fileName, String format, long fileSize) {
    return new MediaMetadata(
        fileName, format, fileSize, "videos", LocalDateTime.of(2026, 10, 5, 10, 0));
  }

  private void mockInventory(Path root, DiscoveryResult... results) throws Exception {
    when(discoveryService.discoverDirectory(root))
        .thenReturn(new MigrationFileInventory(List.of(results)));
  }

  @Test
  void shouldMatchValidManifestFile() throws Exception {

    Path csvPath = tempDir.resolve("metadata.csv");
    Path root = tempDir.resolve("files");

    MetadataCsvRecord record = csvRecord("video01.mp4", "MP4", 1000L);

    MediaMetadata actual = actualMetadata("video01.mp4", "MP4", 1000L);

    when(csvService.parseValidatedCsv(csvPath)).thenReturn(List.of(record));

    mockInventory(root, DiscoveryResult.accepted(root.resolve("videos/video01.mp4"), actual));

    List<ManifestMatchResult> results = manifestService.matchManifest(csvPath, root);

    assertEquals(1, results.size());
    assertTrue(results.get(0).isSuccess());
    assertEquals("SUCCESS", results.get(0).validationStatus());
    assertNull(results.get(0).checkFailed());
  }

  @Test
  void shouldReportFileNotFound() throws Exception {

    Path csvPath = tempDir.resolve("metadata.csv");
    Path root = tempDir.resolve("files");

    when(csvService.parseValidatedCsv(csvPath))
        .thenReturn(List.of(csvRecord("missing.mp4", "MP4", 1000L)));

    mockInventory(root);

    List<ManifestMatchResult> results = manifestService.matchManifest(csvPath, root);

    assertEquals("MISMATCH", results.get(0).validationStatus());
    assertEquals("FILE_NOT_FOUND", results.get(0).checkFailed());
    assertFalse(results.get(0).isSuccess());
  }

  @Test
  void shouldReportFileSizeMismatch() throws Exception {

    Path csvPath = tempDir.resolve("metadata.csv");
    Path root = tempDir.resolve("files");

    when(csvService.parseValidatedCsv(csvPath))
        .thenReturn(List.of(csvRecord("video01.mp4", "MP4", 1000L)));

    mockInventory(
        root,
        DiscoveryResult.accepted(
            root.resolve("video01.mp4"), actualMetadata("video01.mp4", "MP4", 2000L)));

    List<ManifestMatchResult> results = manifestService.matchManifest(csvPath, root);

    assertEquals("FILE_SIZE", results.get(0).checkFailed());
    assertEquals("1000", results.get(0).expectedValue());
  }

  @Test
  void shouldReportFileTypeMismatch() throws Exception {

    Path csvPath = tempDir.resolve("metadata.csv");
    Path root = tempDir.resolve("files");

    when(csvService.parseValidatedCsv(csvPath))
        .thenReturn(List.of(csvRecord("video01.mp4", "AVI", 1000L)));

    mockInventory(
        root,
        DiscoveryResult.accepted(
            root.resolve("video01.mp4"), actualMetadata("video01.mp4", "MP4", 1000L)));

    List<ManifestMatchResult> results = manifestService.matchManifest(csvPath, root);

    assertEquals("FILE_TYPE", results.get(0).checkFailed());
    assertEquals("AVI", results.get(0).expectedValue());
  }

  @Test
  void shouldRejectAmbiguousFileName() throws Exception {

    Path csvPath = tempDir.resolve("metadata.csv");
    Path root = tempDir.resolve("files");

    when(csvService.parseValidatedCsv(csvPath))
        .thenReturn(List.of(csvRecord("video01.mp4", "MP4", 1000L)));

    mockInventory(
        root,
        DiscoveryResult.accepted(
            root.resolve("folderA/video01.mp4"), actualMetadata("video01.mp4", "MP4", 1000L)),
        DiscoveryResult.accepted(
            root.resolve("folderB/video01.mp4"), actualMetadata("video01.mp4", "MP4", 1000L)));

    List<ManifestMatchResult> results = manifestService.matchManifest(csvPath, root);

    assertEquals("MISMATCH", results.get(0).validationStatus());
    assertEquals("AMBIGUOUS_FILE_NAME", results.get(0).checkFailed());
    assertFalse(results.get(0).isSuccess());
  }

  @Test
  void shouldNotScanWhenCsvValidationFails() throws Exception {

    Path csvPath = tempDir.resolve("invalid.csv");
    Path root = tempDir.resolve("files");

    when(csvService.parseValidatedCsv(csvPath))
        .thenThrow(
            new MetadataCsvValidationException(
                List.of(new MetadataCsvValidationError(2, "File Size", "Invalid numeric value"))));

    assertThrows(
        MetadataCsvValidationException.class, () -> manifestService.matchManifest(csvPath, root));

    verify(discoveryService, never()).discoverDirectory(any(Path.class));
  }
}
