package mmiv.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import mmiv.csv.MetadataCsvRecord;
import mmiv.csv.MetadataCsvService;
import mmiv.csv.MetadataCsvValidationException;
import mmiv.model.ManifestMatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.main.web-application-type=none", "discovery.root=/tmp"})
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_ORACLE_IT", matches = "true")
class ManifestProcessingServiceIntegrationTest {

  @TempDir Path tempDir;

  @Autowired private ManifestProcessingService processingService;

  @Autowired private MetadataCsvService csvService;

  @Autowired private JdbcTemplate jdbcTemplate;

  private String uniqueFileName() {
    return "e2e-" + UUID.randomUUID() + ".mp4";
  }

  private Path createFile(Path root, String fileName, byte[] content) throws Exception {

    Files.createDirectories(root);

    Path file = root.resolve(fileName);

    Files.write(file, content);

    return file;
  }

  private Path createManifest(String fileName, long expectedSize) throws Exception {

    Path csvPath = tempDir.resolve(UUID.randomUUID() + ".csv");

    MetadataCsvRecord record =
        new MetadataCsvRecord(
            UUID.randomUUID().toString(),
            fileName,
            "VIDEO",
            "MP4",
            expectedSize,
            LocalDate.now(),
            "integration-test",
            "test",
            "Oracle E2E test");

    csvService.generateCsv(csvPath, List.of(record));

    return csvPath;
  }

  private int countRows(String table, String fileName) {

    if (!table.equals("MEDIA_LOG") && !table.equals("FILE_METADATA")) {
      throw new IllegalArgumentException("Unsupported table");
    }

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE FILE_NAME = ?", Integer.class, fileName);

    return count == null ? 0 : count;
  }

  @Test
  void shouldSaveSuccessLogAndMetadata() throws Exception {

    String fileName = uniqueFileName();

    Path root = tempDir.resolve("success-files");

    createFile(root, fileName, "test-video".getBytes(StandardCharsets.UTF_8));

    long actualSize = Files.size(root.resolve(fileName));

    Path csvPath = createManifest(fileName, actualSize);

    List<ManifestMatchResult> results = processingService.processManifest(csvPath, root);

    assertEquals(1, results.size());
    assertTrue(results.get(0).isSuccess());

    assertEquals(1, countRows("MEDIA_LOG", fileName));

    assertEquals(1, countRows("FILE_METADATA", fileName));

    String status =
        jdbcTemplate.queryForObject(
            """
                SELECT VALIDATION_STATUS
                FROM MEDIA_LOG
                WHERE FILE_NAME = ?
                """,
            String.class,
            fileName);

    assertEquals("SUCCESS", status);
  }

  @Test
  void shouldSaveMismatchWithoutMetadata() throws Exception {

    String fileName = uniqueFileName();

    Path root = tempDir.resolve("mismatch-files");

    createFile(root, fileName, "test-video".getBytes(StandardCharsets.UTF_8));

    long actualSize = Files.size(root.resolve(fileName));

    Path csvPath = createManifest(fileName, actualSize + 1000);

    List<ManifestMatchResult> results = processingService.processManifest(csvPath, root);

    assertEquals(1, results.size());

    assertEquals("FILE_SIZE", results.get(0).checkFailed());

    assertEquals(1, countRows("MEDIA_LOG", fileName));

    assertEquals(0, countRows("FILE_METADATA", fileName));

    String status =
        jdbcTemplate.queryForObject(
            """
                SELECT VALIDATION_STATUS
                FROM MEDIA_LOG
                WHERE FILE_NAME = ?
                """,
            String.class,
            fileName);

    assertEquals("MISMATCH", status);
  }

  @Test
  void shouldNotWriteDatabaseForInvalidCsv() throws Exception {

    String fileName = uniqueFileName();

    Path root = tempDir.resolve("invalid-files");

    createFile(root, fileName, "test-video".getBytes(StandardCharsets.UTF_8));

    Path csvPath = tempDir.resolve("invalid-metadata.csv");

    String content =
        """
                Media ID,File Name,Media Type,Format,File Size,Created Date,Uploaded By,Tags,Description
                M001,%s,VIDEO,MP4,abc,2026-10-05,user01,test,Invalid size
                """
            .formatted(fileName);

    Files.writeString(csvPath, content, StandardCharsets.UTF_8);

    assertThrows(
        MetadataCsvValidationException.class,
        () -> processingService.processManifest(csvPath, root));

    assertEquals(0, countRows("MEDIA_LOG", fileName));

    assertEquals(0, countRows("FILE_METADATA", fileName));
  }

  @Test
  void shouldLogFileNotFoundWithoutMetadata() throws Exception {

    String fileName = uniqueFileName();

    Path root = tempDir.resolve("missing-files");

    // Create an empty directory without the expected file.
    Files.createDirectories(root);

    Path csvPath = createManifest(fileName, 1000L);

    List<ManifestMatchResult> results = processingService.processManifest(csvPath, root);

    // Verify matching result.
    assertEquals(1, results.size());
    assertFalse(results.get(0).isSuccess());
    assertEquals("MISMATCH", results.get(0).validationStatus());
    assertEquals("FILE_NOT_FOUND", results.get(0).checkFailed());

    // Verify Oracle MEDIA_LOG.
    assertEquals(1, countRows("MEDIA_LOG", fileName));

    // Missing files must not enter FILE_METADATA.
    assertEquals(0, countRows("FILE_METADATA", fileName));

    String checkFailed =
        jdbcTemplate.queryForObject(
            """
                SELECT CHECK_FAILED
                FROM MEDIA_LOG
                WHERE FILE_NAME = ?
                """,
            String.class,
            fileName);

    assertEquals("FILE_NOT_FOUND", checkFailed);
  }

  @Test
  void shouldLogAmbiguousFileNameWithoutMetadata() throws Exception {

    String fileName = uniqueFileName();

    Path root = tempDir.resolve("ambiguous-files");

    Path folderA = root.resolve("folderA");
    Path folderB = root.resolve("folderB");

    // Create two files with the same name in different folders.
    createFile(folderA, fileName, "video-A".getBytes(StandardCharsets.UTF_8));

    createFile(folderB, fileName, "video-B".getBytes(StandardCharsets.UTF_8));

    Path csvPath = createManifest(fileName, Files.size(folderA.resolve(fileName)));

    List<ManifestMatchResult> results = processingService.processManifest(csvPath, root);

    // Verify matching result.
    assertEquals(1, results.size());
    assertFalse(results.get(0).isSuccess());

    assertEquals("MISMATCH", results.get(0).validationStatus());

    assertEquals("AMBIGUOUS_FILE_NAME", results.get(0).checkFailed());

    // Verify Oracle MEDIA_LOG.
    assertEquals(1, countRows("MEDIA_LOG", fileName));

    // Ambiguous files must not enter FILE_METADATA.
    assertEquals(0, countRows("FILE_METADATA", fileName));

    String checkFailed =
        jdbcTemplate.queryForObject(
            """
                SELECT CHECK_FAILED
                FROM MEDIA_LOG
                WHERE FILE_NAME = ?
                """,
            String.class,
            fileName);

    assertEquals("AMBIGUOUS_FILE_NAME", checkFailed);
  }

  @Test
  void shouldNotDuplicateMetadataWhenProcessingTwice() throws Exception {

    String fileName = uniqueFileName();

    Path root = tempDir.resolve("duplicate-files");

    createFile(root, fileName, "test-video".getBytes(StandardCharsets.UTF_8));

    long actualSize = Files.size(root.resolve(fileName));

    Path csvPath = createManifest(fileName, actualSize);

    // First processing run.
    List<ManifestMatchResult> firstRun = processingService.processManifest(csvPath, root);

    assertEquals(1, firstRun.size());
    assertTrue(firstRun.get(0).isSuccess());

    assertEquals(1, countRows("FILE_METADATA", fileName));

    assertEquals(1, countRows("MEDIA_LOG", fileName));

    // Second processing run using the same CSV.
    List<ManifestMatchResult> secondRun = processingService.processManifest(csvPath, root);

    assertEquals(1, secondRun.size());
    assertTrue(secondRun.get(0).isSuccess());

    // Metadata must not be inserted twice.
    assertEquals(1, countRows("FILE_METADATA", fileName));

    // Each validation run must have its own log.
    assertEquals(2, countRows("MEDIA_LOG", fileName));
  }
}
